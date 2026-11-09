package ru.azenizzka.services;

import jakarta.annotation.PostConstruct;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import ru.azenizzka.configuration.TelegramBotConfiguration;
import ru.azenizzka.entities.Person;
import ru.azenizzka.telegram.TelegramBot;
import ru.azenizzka.utils.Day;
import ru.azenizzka.utils.DayUtil;
import ru.azenizzka.utils.MessagesConfig;

@Service
@Slf4j
@RequiredArgsConstructor
public class LessonScheduleService {

  private final PersonService personService;
  private final BellScheduleService bellScheduleService;
  private final TelegramBotConfiguration botConfiguration;

  @Value("${schedule.url:https://ntmm.ru/5_screen.files/sheet001.htm}")
  private String scheduleUrl;

  private static final SSLSocketFactory SSL_SOCKET_FACTORY = createInsecureSslSocketFactory();
  private static final Pattern WHITESPACE_PATTERN = Pattern.compile("\\s+");
  private static final Pattern GROUP_PATTERN = Pattern.compile("(?<!\\d)\\d{2,3}(?!\\d)");

  private final Map<String, Map<Day, String>> scheduleCache = new ConcurrentHashMap<>();
  private final Map<String, Map<Day, List<List<String>>>> rawLessonsCache =
      new ConcurrentHashMap<>();

  @PostConstruct
  public void init() {
    CompletableFuture.runAsync(this::updateCache);
  }

  @Scheduled(fixedRate = 10 * 60 * 1000, initialDelay = 10 * 60 * 1000)
  public void scheduledCacheUpdate() {
    updateCache();
  }

  // Ежедневно в 08:00 по Новосибирску (ПН-СБ) — расписание на СЕГОДНЯ в групповые чаты с закрепом
  @Scheduled(cron = "0 0 6 * * MON-SAT", zone = "Asia/Novosibirsk")
  public void sendMorningGroupSchedules() {
    try {
      Day today = DayUtil.convertStrToDay("сегодня");
      broadcastPinnedToGroups(today, "сегодня (" + formatDayRus(today) + ")");
    } catch (Exception e) {
      log.error("Ошибка утренней рассылки в группы: {}", e.getMessage());
    }
  }

  // Ежедневно в 18:00 по Новосибирску (ПН-СБ) — расписание на ЗАВТРА в групповые чаты с закрепом
  @Scheduled(cron = "0 0 16 * * MON-SAT", zone = "Asia/Novosibirsk")
  public void sendEveningGroupSchedules() {
    try {
      Day tomorrow = DayUtil.convertStrToDay("завтра");
      broadcastPinnedToGroups(tomorrow, "завтра (" + formatDayRus(tomorrow) + ")");
    } catch (Exception e) {
      log.error("Ошибка вечерней рассылки в группы: {}", e.getMessage());
    }
  }

  private void broadcastPinnedToGroups(Day day, String label) {
    if (TelegramBot.getInstance() == null) return;

    CompletableFuture.runAsync(
        () -> {
          for (Person p : personService.findAll()) {
            if (p.getChatId() != null
                && p.getChatId().startsWith("-")
                && !p.getChatId().equals(botConfiguration.getAuditLogChatId())
                && p.getGroupNum() != 0
                && p.isNotificationsEnabled()
                && !p.isBanned()) {
              String text =
                  buildDetailedScheduleText(p.getGroupNum(), day, label, p.isMinimalistMode());
              TelegramBot.getInstance().sendAndPinSchedule(p, text);
              try {
                Thread.sleep(60L);
              } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
              }
            }
          }
        });
  }

  public String buildDetailedScheduleText(int groupNum, Day day, String label, boolean minimalist) {
    List<List<String>> lessons = getLessons(groupNum, day);
    if (lessons.isEmpty()) {
      return "📌 *Расписание "
          + groupNum
          + " группы на "
          + label
          + "*\n\n"
          + MessagesConfig.NO_LESSONS_MESSAGE;
    }

    StringBuilder result =
        new StringBuilder("📌 *Расписание " + groupNum + " группы*\n*" + label + "*\n\n");
    for (List<String> list : lessons) {
      int lessonNum = 0;
      try {
        lessonNum = Integer.parseInt(list.get(0).replaceAll("[^0-9]", ""));
      } catch (Exception ignored) {
      }
      String bellTime = bellScheduleService.getBellTime(day, lessonNum);
      result.append("*").append(list.get(0)).append(" пара:* ").append(list.get(1)).append("\n");
      result.append("*Кабинет:* ").append(list.get(2)).append("\n");
      if (!bellTime.isBlank()) {
        result.append(minimalist ? "`" : "⏱ `").append(bellTime).append("`\n");
      }
      result.append("\n");
    }
    return result.toString().trim();
  }

  private String formatDayRus(Day day) {
    return switch (day) {
      case MONDAY -> "Понедельник";
      case TUESDAY -> "Вторник";
      case WEDNESDAY -> "Среда";
      case THURSDAY -> "Четверг";
      case FRIDAY -> "Пятница";
      case SATURDAY -> "Суббота";
      default -> day.name();
    };
  }

  private Document fetchDocumentWithRetry(String url) throws Exception {
    Exception lastEx = null;
    for (int attempt = 1; attempt <= 3; attempt++) {
      try {
        return Jsoup.connect(url)
            .sslSocketFactory(SSL_SOCKET_FACTORY)
            .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
            .timeout(60_000)
            .maxBodySize(0)
            .get();
      } catch (Exception e) {
        lastEx = e;
        if (attempt < 3) {
          try {
            Thread.sleep(1000L * attempt);
          } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw e;
          }
        }
      }
    }
    if (url.endsWith(".files/sheet001.htm")) {
      String fallbackUrl = url.replace(".files/sheet001.htm", ".htm");
      try {
        log.info("Попытка загрузки альтернативного URL: {}", fallbackUrl);
        return Jsoup.connect(fallbackUrl)
            .sslSocketFactory(SSL_SOCKET_FACTORY)
            .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
            .timeout(60_000)
            .maxBodySize(0)
            .get();
      } catch (Exception ignored) {
      }
    }
    throw lastEx;
  }

  private String resolveAbsoluteUrl(String baseUrl, String relativeUrl) {
    if (relativeUrl.startsWith("http")) {
      return relativeUrl;
    }
    if (relativeUrl.startsWith("/")) {
      return "https://ntmm.ru" + relativeUrl;
    }
    int lastSlash = baseUrl.lastIndexOf('/');
    String baseDir = lastSlash != -1 ? baseUrl.substring(0, lastSlash + 1) : "https://ntmm.ru/";
    return baseDir + relativeUrl;
  }

  private List<String> discoverSheetUrls() {
    log.info("Запуск обнаружения ссылок на таблицы расписания (discoverSheetUrls)...");
    Set<String> urls = new LinkedHashSet<>();
    try {
      log.info("Загрузка страницы со списком расписаний: https://ntmm.ru/student/raspisanie.php");
      Document indexDoc = fetchDocumentWithRetry("https://ntmm.ru/student/raspisanie.php");

      for (Element a : indexDoc.select("a[href]")) {
        String href = a.attr("href").trim();
        String cleanHref = href.split("\\?")[0].trim();
        if (cleanHref.toLowerCase().contains("/incoming/r-oo/")
            && (cleanHref.toLowerCase().endsWith(".htm")
                || cleanHref.toLowerCase().endsWith(".html"))) {
          String fullUrl = resolveAbsoluteUrl("https://ntmm.ru/student/raspisanie.php", cleanHref);
          String sheetUrl = fullUrl.replaceAll("(?i)\\.html?$", ".files/sheet001.htm");
          urls.add(sheetUrl);
          log.info("Обнаружена ссылка на расписание: {} -> {}", cleanHref, sheetUrl);
        }
      }
      log.info("Успешно обнаружено {} уникальных ссылок на расписания", urls.size());
    } catch (Exception e) {
      log.warn("Не удалось получить список ссылок с raspisanie.php: {}", e.getMessage(), e);
    }

    if (urls.isEmpty()) {
      log.warn("Список ссылок пуст! Используются резервные дефолтные ссылки...");
      String baseUrl = "https://ntmm.ru/incoming/R-OO/";
      for (int i = 1; i <= 8; i++) {
        if (i == 2) {
          urls.add(baseUrl + "2_screen1.files/sheet001.htm");
        }
        urls.add(baseUrl + i + "_screen.files/sheet001.htm");
      }
      log.info("Добавлено {} резервных ссылок", urls.size());
    }

    return new ArrayList<>(urls);
  }

  public synchronized void updateCache() {
    log.info("Запуск обновления кэша расписания...");

    Map<String, Map<Day, String>> newScheduleCache = new HashMap<>();
    Map<String, Map<Day, List<List<String>>>> newRawCache = new HashMap<>();

    List<String> sheetUrls = discoverSheetUrls();
    log.info("Найдено URL таблиц для парсинга: {}", sheetUrls.size());
    int failedUrls = 0;

    for (int i = 0; i < sheetUrls.size(); i++) {
      String url = sheetUrls.get(i);
      log.info("[{}/{}] Начинаем парсинг страницы расписания: {}", i + 1, sheetUrls.size(), url);
      try {
        Document doc = fetchDocumentWithRetry(url);

        Element table = doc.selectFirst("table");
        if (table == null) {
          log.warn("Таблица расписания не найдена по адресу {}", url);
          continue;
        }

        int groupsBefore = newScheduleCache.size();
        String[][] grid = buildMatrix(table);
        parseMatrix(grid, newScheduleCache, newRawCache);
        int groupsAfter = newScheduleCache.size();
        log.info(
            "Успешно спарсили {}. Добавлено/обновлено групп: {} (всего в новом кэше: {})",
            url,
            groupsAfter - groupsBefore,
            groupsAfter);

      } catch (Exception e) {
        failedUrls++;
        log.error("Ошибка при обновлении кэша с {}: {}", url, e.getMessage(), e);
      }
    }

    log.info(
        "Парсинг всех страниц завершен. Успешно: {}, Ошибок: {}, Всего групп спарсено: {}",
        sheetUrls.size() - failedUrls,
        failedUrls,
        newScheduleCache.size());

    if (!newScheduleCache.isEmpty()) {
      Map<String, Map<Day, String>> oldCacheSnapshot = new HashMap<>();
      for (Map.Entry<String, Map<Day, String>> entry : scheduleCache.entrySet()) {
        oldCacheSnapshot.put(entry.getKey(), new HashMap<>(entry.getValue()));
      }
      boolean wasInitialized = !oldCacheSnapshot.isEmpty();

      if (failedUrls == 0) {
        scheduleCache.clear();
        rawLessonsCache.clear();
      }
      scheduleCache.putAll(newScheduleCache);
      rawLessonsCache.putAll(newRawCache);

      if (wasInitialized) {
        checkAndNotifyChanges(newScheduleCache, oldCacheSnapshot);
      }

      log.info("Кэш успешно обновлен. Загружено групп: {}", scheduleCache.size());
    } else {
      log.warn("Не удалось загрузить расписание ни с одной страницы!");
    }
  }

  private void checkAndNotifyChanges(
      Map<String, Map<Day, String>> newScheduleCache,
      Map<String, Map<Day, String>> oldScheduleCache) {
    Day[] studyDays = {
      Day.MONDAY, Day.TUESDAY, Day.WEDNESDAY, Day.THURSDAY, Day.FRIDAY, Day.SATURDAY
    };

    for (Map.Entry<String, Map<Day, String>> groupEntry : newScheduleCache.entrySet()) {
      String groupStr = groupEntry.getKey();
      Map<Day, String> newDays = groupEntry.getValue();
      Map<Day, String> oldDays = oldScheduleCache.get(groupStr);

      if (oldDays == null) continue;

      int groupNum;
      try {
        groupNum = Integer.parseInt(groupStr);
      } catch (NumberFormatException e) {
        continue;
      }

      for (Day day : studyDays) {
        String newText = newDays.getOrDefault(day, "").trim();
        String oldText = oldDays.getOrDefault(day, "").trim();

        if (!newText.equals(oldText)) {
          log.info("Обнаружено изменение расписания для группы {} на {}", groupNum, day);
          notifyStudentsOnScheduleChange(groupNum, day, newText);
        }
      }
    }
  }

  public void applyTestScheduleChange(int groupNum) {
    notifyStudentsOnScheduleChange(
        groupNum,
        Day.MONDAY,
        "⚠️ *ТЕСТОВОЕ ИЗМЕНЕНИЕ РАСПИСАНИЯ* ⚠️\n\n1. Тестовая пара 1\n2. Тестовая пара 2");
  }

  public void notifyStudentsOnScheduleChange(int groupNum, Day day, String newScheduleText) {
    String dayRus = formatDayRus(day);
    String displaySchedule =
        (newScheduleText == null || newScheduleText.isBlank())
            ? MessagesConfig.NO_LESSONS_MESSAGE
            : newScheduleText;

    List<SendMessage> notifications = new ArrayList<>();
    for (Person p : personService.findAll()) {
      if (p.getGroupNum() == groupNum && p.isNotificationsEnabled() && !p.isBanned()) {
        if (p.getChatId() != null && p.getChatId().startsWith("-")) {
          if (TelegramBot.getInstance() != null
              && !p.getChatId().equals(botConfiguration.getAuditLogChatId())) {
            String pinText =
                "🔔 *Обновление расписания!*\n\n"
                    + buildDetailedScheduleText(groupNum, day, dayRus, p.isMinimalistMode());
            TelegramBot.getInstance().sendAndPinSchedule(p, pinText);
          }
        } else {
          SendMessage msg =
              new SendMessage(
                  p.getChatId(),
                  "🔔 *Обнаружено изменение в расписании!*\n"
                      + "🎓 Группа: *"
                      + groupNum
                      + "*\n"
                      + "📅 День: *"
                      + dayRus
                      + "*\n\n"
                      + "*Новое расписание:*\n"
                      + displaySchedule);
          msg.enableMarkdown(true);
          notifications.add(msg);
        }
      }
    }
    if (!notifications.isEmpty() && TelegramBot.getInstance() != null) {
      TelegramBot.getInstance().broadcastAsync(notifications, null);
    }
  }

  public boolean isGroupExists(int group) {
    return isGroupExists(String.valueOf(group));
  }

  public boolean isGroupExists(String group) {
    if (group == null || group.isBlank()) return false;
    return scheduleCache.containsKey(group.trim()) || rawLessonsCache.containsKey(group.trim());
  }

  public List<List<String>> getLessons(int groupNum, Day day) {
    return getLessons(String.valueOf(groupNum), day);
  }

  public List<List<String>> getLessons(String groupNum, Day day) {
    if (groupNum == null || day == null) return Collections.emptyList();
    Map<Day, List<List<String>>> days = rawLessonsCache.get(groupNum.trim());
    if (days == null) return Collections.emptyList();
    return days.getOrDefault(day, Collections.emptyList());
  }

  public String getSchedule(int groupNum, Day day) {
    return getSchedule(String.valueOf(groupNum), day);
  }

  public String getSchedule(String groupNum, Day day) {
    if (groupNum == null || groupNum.isBlank()) {
      return MessagesConfig.GROUP_NOT_DEFINED_EXCEPTION;
    }

    Map<Day, String> groupDays = scheduleCache.get(groupNum.trim());
    if (groupDays == null) {
      return MessagesConfig.GROUP_NOT_FOUND_EXCEPTION;
    }

    return groupDays.getOrDefault(day, MessagesConfig.NO_LESSONS_MESSAGE);
  }

  private String[][] buildMatrix(Element table) {
    Elements trs = table.select("tr");
    int rowCount = trs.size();
    int colCount = 200;

    log.info(
        "Построение матрицы таблицы: найдено {} строк (tr), фиксированная ширина {} колонок",
        rowCount,
        colCount);

    String[][] grid = new String[rowCount][colCount];

    for (int r = 0; r < rowCount; r++) {
      Element tr = trs.get(r);
      Elements cells = tr.children();
      int c = 0;

      for (Element cell : cells) {
        if (!cell.tagName().equalsIgnoreCase("td") && !cell.tagName().equalsIgnoreCase("th")) {
          continue;
        }

        while (c < colCount && grid[r][c] != null) {
          c++;
        }

        if (c >= colCount) break;

        int rowspan = 1;
        int colspan = 1;
        try {
          if (cell.hasAttr("rowspan")) rowspan = Integer.parseInt(cell.attr("rowspan").trim());
        } catch (NumberFormatException ignored) {
        }
        try {
          if (cell.hasAttr("colspan")) colspan = Integer.parseInt(cell.attr("colspan").trim());
        } catch (NumberFormatException ignored) {
        }
        String text = cell.text().replace("\u00A0", " ").trim();

        for (int dr = 0; dr < rowspan && (r + dr) < rowCount; dr++) {
          for (int dc = 0; dc < colspan && (c + dc) < colCount; dc++) {
            grid[r + dr][c + dc] = text;
          }
        }
        c += colspan;
      }
    }
    log.info("Матрица таблицы успешно построена: размерность {}x{}", rowCount, colCount);
    return grid;
  }

  private boolean isColumnPairNumber(String[][] grid, int startRow, int col) {
    if (col < 0) return false;
    int pairDigits = 0;
    for (int r = startRow; r < Math.min(startRow + 25, grid.length); r++) {
      if (col >= grid[r].length || grid[r][col] == null) continue;
      String val = grid[r][col].trim();
      if (val.equalsIgnoreCase("н/п") || val.equalsIgnoreCase("№")) {
        return true;
      }
      if (val.length() == 1 && val.charAt(0) >= '1' && val.charAt(0) <= '6') {
        pairDigits++;
      }
    }
    return pairDigits >= 2;
  }

  private String sanitizeMarkdown(String text) {
    if (text == null) return "";
    String step1 = text.replace("*", "").replace("_", " ").replace("`", "'");
    return WHITESPACE_PATTERN.matcher(step1).replaceAll(" ").trim();
  }

  private boolean isDate(String cell) {
    if (cell == null || cell.length() < 5) return false;
    // Быстрая проверка на формат типа dd.MM (например, 12.09)
    for (int i = 0; i < cell.length() - 4; i++) {
      if (Character.isDigit(cell.charAt(i))
          && Character.isDigit(cell.charAt(i + 1))
          && cell.charAt(i + 2) == '.'
          && Character.isDigit(cell.charAt(i + 3))
          && Character.isDigit(cell.charAt(i + 4))) {
        return true;
      }
    }
    return false;
  }

  private void parseMatrix(
      String[][] grid,
      Map<String, Map<Day, String>> newScheduleCache,
      Map<String, Map<Day, List<List<String>>>> newRawCache) {
    log.info(
        "Начало разбора матрицы расписания (размер {}x{})...",
        grid.length,
        grid.length > 0 ? grid[0].length : 0);
    int groupRowIndex = -1;
    int maxDistinctGroups = 0;
    int searchLimit = Math.min(30, Math.max(1, grid.length - 2));
    for (int r = 0; r < searchLimit; r++) {
      Set<String> distinctGroups = new HashSet<>();
      for (int c = 0; c < grid[r].length; c++) {
        String cell = grid[r][c];
        if (cell != null && !cell.isBlank() && cell.length() <= 25 && !isDate(cell)) {
          Matcher m = GROUP_PATTERN.matcher(cell);
          if (m.find()) {
            distinctGroups.add(m.group());
          }
        }
      }
      if (distinctGroups.size() > maxDistinctGroups && distinctGroups.size() >= 1) {
        maxDistinctGroups = distinctGroups.size();
        groupRowIndex = r;
      }
    }

    if (groupRowIndex == -1) {
      log.warn("Строка с номерами групп не найдена среди первых {} строк таблицы", searchLimit);
      return;
    }
    log.info(
        "Строка с номерами групп определена: index={}, обнаружено {} групп",
        groupRowIndex,
        maxDistinctGroups);

    Map<Integer, String> colToGroup = new LinkedHashMap<>();
    for (int c = 0; c < grid[groupRowIndex].length; c++) {
      String cell = grid[groupRowIndex][c];
      if (cell != null && !cell.isBlank() && cell.length() <= 25 && !isDate(cell)) {
        Matcher m = GROUP_PATTERN.matcher(cell);
        if (m.find()) {
          String groupNum = m.group();
          if (!colToGroup.containsValue(groupNum)) {
            int startCol = c;
            if (!isColumnPairNumber(grid, groupRowIndex + 1, c)
                && isColumnPairNumber(grid, groupRowIndex + 1, c - 1)
                && !colToGroup.containsKey(c - 1)) {
              startCol = c - 1;
            }
            colToGroup.put(startCol, groupNum);
            newScheduleCache.computeIfAbsent(groupNum, k -> new HashMap<>());
            newRawCache.computeIfAbsent(groupNum, k -> new HashMap<>());
          }
        }
      }
    }
    log.info(
        "Колонки групп успешно определены (всего {} групп): {}", colToGroup.size(), colToGroup);

    int firstGroupCol = colToGroup.keySet().stream().min(Integer::compareTo).orElse(4);
    Map<Integer, Day> rowToDay = new HashMap<>();

    Day currentDay = null;
    int firstDayRow = -1;
    Day firstDetectedDay = null;
    int currentDayRowCount = 0;

    for (int r = groupRowIndex + 1; r < grid.length; r++) {
      Day rowExplicitDay = null;
      for (int c = 0; c < Math.min(firstGroupCol, grid[r].length); c++) {
        Day parsed = parseDay(grid[r][c]);
        if (parsed != null) {
          rowExplicitDay = parsed;
          break;
        }
      }

      if (rowExplicitDay != null) {
        if (currentDay == null || rowExplicitDay != currentDay) {
          currentDay = rowExplicitDay;
          currentDayRowCount = 0;
          if (firstDetectedDay == null) {
            firstDetectedDay = currentDay;
            firstDayRow = r;
          }
        }
      } else if (currentDay != null && currentDayRowCount >= 4) {
        String rowPair = findRowPairNumber(grid, r, firstGroupCol);
        if ("1".equals(rowPair)) {
          Day nextDay = getNextStudyDay(currentDay);
          if (nextDay != null) {
            currentDay = nextDay;
            currentDayRowCount = 0;
          }
        }
      }

      if (currentDay != null) {
        rowToDay.put(r, currentDay);
        currentDayRowCount++;
      }
    }

    if (firstDayRow > groupRowIndex + 1 && firstDetectedDay != null) {
      Day prevDay = getPreviousStudyDay(firstDetectedDay);
      if (prevDay != null) {
        for (int r = groupRowIndex + 1; r < firstDayRow; r++) {
          rowToDay.put(r, prevDay);
        }
      }
    }

    int totalLessonsExtracted = 0;
    for (int r = groupRowIndex + 1; r < grid.length; r++) {
      Day currentDayRow = rowToDay.get(r);
      if (currentDayRow == null) continue;

      for (Map.Entry<Integer, String> entry : colToGroup.entrySet()) {
        int gCol = entry.getKey();
        String groupNum = entry.getValue();

        String pairNum =
            (gCol < grid[r].length && grid[r][gCol] != null) ? sanitizeMarkdown(grid[r][gCol]) : "";
        String discipline =
            (gCol + 1 < grid[r].length && grid[r][gCol + 1] != null)
                ? sanitizeMarkdown(grid[r][gCol + 1])
                : "";
        String room =
            (gCol + 2 < grid[r].length && grid[r][gCol + 2] != null)
                ? sanitizeMarkdown(grid[r][gCol + 2])
                : "";

        boolean isPairDigit = pairNum.matches("^[1-6](\\s*(пара|п\\.?|\\.)|-[1-6])?$");
        if (!isPairDigit && !pairNum.isBlank()) {
          room = discipline;
          discipline = pairNum;
          pairNum = findRowPairNumber(grid, r, firstGroupCol);
        } else if (pairNum.isBlank()) {
          pairNum = findRowPairNumber(grid, r, firstGroupCol);
        } else {
          pairNum = pairNum.replaceAll("[^0-9]", "");
        }

        if (!discipline.isBlank()
            && !discipline.equalsIgnoreCase("н/п")
            && !discipline.equalsIgnoreCase("№")
            && !discipline.toLowerCase().startsWith("дисциплин")
            && !discipline.toLowerCase().startsWith("предмет")
            && !discipline.toLowerCase().startsWith("кабинет")
            && !discipline.toLowerCase().startsWith("аудитор")) {
          newScheduleCache.computeIfAbsent(groupNum, k -> new HashMap<>());
          Map<Day, String> dayMap = newScheduleCache.get(groupNum);

          newRawCache.computeIfAbsent(groupNum, k -> new HashMap<>());
          Map<Day, List<List<String>>> rawDayMap = newRawCache.get(groupNum);
          List<List<String>> dayLessons =
              rawDayMap.computeIfAbsent(currentDayRow, k -> new ArrayList<>());

          List<String> lessonTuple = List.of(pairNum, discipline, room);
          if (dayLessons.contains(lessonTuple)) {
            continue;
          }
          dayLessons.add(lessonTuple);
          totalLessonsExtracted++;

          String currentSchedule = dayMap.getOrDefault(currentDayRow, "");
          String lessonLine =
              String.format(
                  "%s. %s %s\n",
                  pairNum.isBlank() ? "•" : pairNum,
                  discipline,
                  room.isBlank() ? "" : "(" + room + ")");
          dayMap.put(currentDayRow, currentSchedule + lessonLine);
        }
      }
    }
    log.info(
        "Разбор матрицы завершен: извлечено {} занятий для {} групп",
        totalLessonsExtracted,
        colToGroup.size());
  }

  private Day parseDay(String text) {
    if (text == null) return null;
    String t = text.replace("\u00A0", " ").trim().toUpperCase();
    String clean = t.replaceAll("[^\\p{L}\\p{Nd}]+", " ").trim();
    for (String word : clean.split("\\s+")) {
      if (word.startsWith("ПОНЕД") || word.equals("ПН")) return Day.MONDAY;
      if (word.startsWith("ВТОРН") || word.equals("ВТ")) return Day.TUESDAY;
      if (word.startsWith("СРЕД") || word.equals("СР")) return Day.WEDNESDAY;
      if (word.startsWith("ЧЕТВ") || word.equals("ЧТ")) return Day.THURSDAY;
      if (word.startsWith("ПЯТН") || word.equals("ПТ")) return Day.FRIDAY;
      if (word.startsWith("СУББ") || word.equals("СБ")) return Day.SATURDAY;
    }
    return null;
  }

  private Day getNextStudyDay(Day day) {
    return switch (day) {
      case MONDAY -> Day.TUESDAY;
      case TUESDAY -> Day.WEDNESDAY;
      case WEDNESDAY -> Day.THURSDAY;
      case THURSDAY -> Day.FRIDAY;
      case FRIDAY -> Day.SATURDAY;
      default -> null;
    };
  }

  private Day getPreviousStudyDay(Day day) {
    return switch (day) {
      case TUESDAY -> Day.MONDAY;
      case WEDNESDAY -> Day.TUESDAY;
      case THURSDAY -> Day.WEDNESDAY;
      case FRIDAY -> Day.THURSDAY;
      case SATURDAY -> Day.FRIDAY;
      default -> null;
    };
  }

  private String findRowPairNumber(String[][] grid, int r, int maxCol) {
    for (int c = 0; c < Math.min(maxCol, grid[r].length); c++) {
      String val = grid[r][c];
      if (val != null) {
        String t = val.trim();
        if (t.matches("^[1-6](\\s*(пара|п\\.?|\\.)|-[1-6])?$")) {
          return t.replaceAll("[^0-9]", "").substring(0, 1);
        }
      }
    }
    return "";
  }

  private static SSLSocketFactory createInsecureSslSocketFactory() {
    TrustManager[] trustAllCerts =
        new TrustManager[] {
          new X509TrustManager() {
            public X509Certificate[] getAcceptedIssuers() {
              return new X509Certificate[0];
            }

            public void checkClientTrusted(X509Certificate[] certs, String authType) {}

            public void checkServerTrusted(X509Certificate[] certs, String authType) {}
          }
        };

    try {
      SSLContext sslContext = SSLContext.getInstance("TLS");
      sslContext.init(null, trustAllCerts, new SecureRandom());
      return sslContext.getSocketFactory();
    } catch (Exception e) {
      throw new IllegalStateException("Ошибка инициализации SSL", e);
    }
  }
}
