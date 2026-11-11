package ru.azenizzka.services;

import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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

  @Value("${schedule.url:https://ntmm.ru/incoming/R-OO/1_screen.files/sheet001.htm}")
  private String scheduleUrl;

  private static final SSLSocketFactory SSL_SOCKET_FACTORY = createInsecureSslSocketFactory();
  private static final Pattern WHITESPACE_PATTERN = Pattern.compile("\\s+");
  private static final Pattern PAIR_DIGIT_PATTERN =
      Pattern.compile("^[0-6](\\s*(пара|п\\.?|\\.)|-[1-6])?$");

  private static final Map<Day, Integer> DAY_OFFSETS =
      Map.of(
          Day.MONDAY, 1,
          Day.TUESDAY, 7,
          Day.WEDNESDAY, 13,
          Day.THURSDAY, 19,
          Day.FRIDAY, 25,
          Day.SATURDAY, 31);

  private static final Day[] STUDY_DAYS = {
    Day.MONDAY, Day.TUESDAY, Day.WEDNESDAY, Day.THURSDAY, Day.FRIDAY, Day.SATURDAY
  };

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
      String rawNum = list.get(0) != null ? list.get(0).trim() : "";
      int lessonNum = 0;
      try {
        lessonNum = Integer.parseInt(rawNum.replaceAll("[^0-9]", ""));
      } catch (Exception ignored) {
      }
      String bellTime = lessonNum > 0 ? bellScheduleService.getBellTime(day, lessonNum) : "";
      String displayNum = rawNum.isBlank() ? "0" : rawNum;
      result.append("*").append(displayNum).append(" пара:* ").append(list.get(1)).append("\n");
      if (list.size() > 2 && list.get(2) != null && !list.get(2).isBlank()) {
        result.append("*Кабинет:* ").append(list.get(2)).append("\n");
      }
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

  public static Document parseRawSheetHtml(String rawHtml) {
    return ru.azenizzka.utils.HtmlTableNormalizer.parseCleanHtml(rawHtml);
  }

  public void parseRawSheetIntoCache(
      String rawHtml,
      Map<String, Map<Day, String>> targetScheduleCache,
      Map<String, Map<Day, List<List<String>>>> targetRawCache) {
    Document doc = parseRawSheetHtml(rawHtml);
    Element table = doc.selectFirst("table");
    if (table == null) {
      return;
    }
    String[][] grid = buildMatrix(table);
    parseMatrix(grid, targetScheduleCache, targetRawCache);
  }

  private Document fetchDocumentWithRetry(String url) throws Exception {
    if (url.startsWith("file://")) {
      String content = Files.readString(Path.of(url.substring(7)), StandardCharsets.UTF_8);
      return parseRawSheetHtml(content);
    }

    Exception lastEx = null;
    for (int attempt = 1; attempt <= 3; attempt++) {
      try {
        String rawBody =
            Jsoup.connect(url)
                .sslSocketFactory(SSL_SOCKET_FACTORY)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .timeout(60_000)
                .maxBodySize(0)
                .execute()
                .body();
        return url.toLowerCase().contains("raspisanie.php")
            ? Jsoup.parse(rawBody, url)
            : parseRawSheetHtml(rawBody);
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
        String rawBody =
            Jsoup.connect(fallbackUrl)
                .sslSocketFactory(SSL_SOCKET_FACTORY)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .timeout(60_000)
                .maxBodySize(0)
                .execute()
                .body();
        return parseRawSheetHtml(rawBody);
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

  private final Map<String, Set<String>> sheetGroupsMap = new ConcurrentHashMap<>();

  private static final Map<String, List<String>> DEFAULT_SHEET_GROUPS =
      Map.of(
          "1_screen",
          List.of("ПУ-101", "С-111", "С-112", "С-113", "С-114", "С-115", "С-116", "Э-121"),
          "2_screen",
          List.of("МР-131", "МР-132", "МР-133", "МР-134", "МР-135", "К-141", "М-151", "РП-161"),
          "3_screen",
          List.of("АТ-181", "АТ-182", "ТГ-191", "С-211", "С-212", "С-213", "С-214", "ПУ-201"),
          "4_screen",
          List.of(
              "С-215", "С-216", "С-217", "ЭГ-21", "Э-221", "МР-231", "МР-232", "МР-233", "МР-234"),
          "5_screen",
          List.of(
              "К-241", "К-242", "М-251", "РП-261", "АТ-281", "АТ-282", "ТГ-291", "ПУ-301",
              "СВ-314"),
          "6_screen",
          List.of(
              "С-311", "С-312", "С-313", "Э-321", "МР-331", "МР-332", "М-351", "РП-361", "РП-461"),
          "7_screen",
          List.of(
              "СА-371", "АТ-381", "ТГ-391", "К-341", "К-342", "РП-561", "ПУ-401", "С-411", "С-412"),
          "8_screen",
          List.of(
              "С-413", "Э-421", "МР-431", "МР-432", "МР-433", "М-451", "Т-471", "АТ-481",
              "ТГ-491"));

  private boolean isIgnoredGroupPrefix(String text) {
    if (text == null || text.isBlank()) {
      return true;
    }
    String lower = text.trim().toLowerCase();
    return lower.equals("каб")
        || lower.equals("каб.")
        || lower.equals("ауд")
        || lower.equals("ауд.")
        || lower.equals("н/п")
        || lower.equals("№")
        || lower.equals("пара")
        || lower.equals("день")
        || lower.startsWith("дисциплин")
        || lower.startsWith("предмет")
        || lower.startsWith("кабинет");
  }

  private Set<String> extractGroupsFromText(String text) {
    if (text == null || text.isBlank()) {
      return Collections.emptySet();
    }
    Pattern p = Pattern.compile("([А-Яа-яЁёA-Za-z]+)[\\s-]*(\\d{2,3})(?!\\d)");
    Matcher m = p.matcher(text);
    Set<String> groups = new LinkedHashSet<>();
    while (m.find()) {
      String letters = m.group(1).replaceAll("[^А-Яа-яЁёA-Za-z]", "").toUpperCase();
      String digits = m.group(2).replaceAll("[^0-9]", "");
      if (!letters.isEmpty() && !digits.isEmpty() && !isIgnoredGroupPrefix(letters)) {
        groups.add(letters + "-" + digits);
      }
    }
    return groups;
  }

  private Set<String> getExpectedGroupsForUrl(String url) {
    Set<String> result = new LinkedHashSet<>();
    if (url != null) {
      for (Map.Entry<String, List<String>> entry : DEFAULT_SHEET_GROUPS.entrySet()) {
        if (url.contains(entry.getKey())) {
          result.addAll(entry.getValue());
          break;
        }
      }
      Set<String> dynamic = sheetGroupsMap.get(url);
      if (dynamic != null && !dynamic.isEmpty()) {
        result.addAll(dynamic);
      }
    }
    return result;
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

          String contextText = a.text();
          Element parent = a.parent();
          while (parent != null
              && !parent.tagName().equalsIgnoreCase("body")
              && !parent.tagName().equalsIgnoreCase("html")) {
            if (parent.text().matches(".*\\d{2,3}.*")) {
              contextText = parent.text();
              break;
            }
            parent = parent.parent();
          }

          Set<String> groups = extractGroupsFromText(contextText);
          if (!groups.isEmpty()) {
            sheetGroupsMap.put(sheetUrl, groups);
            log.info("Для {} найдены группы на raspisanie.php: {}", sheetUrl, groups);
          }
          log.info("Обнаружена ссылка на расписание: {} -> {}", cleanHref, sheetUrl);
        }
      }
      log.info("Успешно обнаружено {} уникальных ссылок на расписания", urls.size());
    } catch (Exception e) {
      log.warn("Не удалось получить список ссылок с raspisanie.php: {}", e.getMessage(), e);
    }

    String baseUrl = "https://ntmm.ru/incoming/R-OO/";
    for (int i = 1; i <= 8; i++) {
      String canonicalUrl =
          i == 2
              ? baseUrl + "2_screen1.files/sheet001.htm"
              : baseUrl + i + "_screen.files/sheet001.htm";
      urls.add(canonicalUrl);
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
        Set<String> expectedGroups = getExpectedGroupsForUrl(url);
        parseMatrix(grid, newScheduleCache, newRawCache, expectedGroups);
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
        groupNum = Integer.parseInt(groupStr.replaceAll("[^0-9]", ""));
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

  private String resolveGroupKey(String group) {
    if (group == null || group.isBlank()) return null;
    String clean = group.trim();
    if (scheduleCache.containsKey(clean) || rawLessonsCache.containsKey(clean)) {
      return clean;
    }
    String digits = clean.replaceAll("[^0-9]", "");
    if (!digits.isEmpty()) {
      for (String key : scheduleCache.keySet()) {
        if (key.endsWith("-" + digits) || key.replaceAll("[^0-9]", "").equals(digits)) {
          return key;
        }
      }
      for (String key : rawLessonsCache.keySet()) {
        if (key.endsWith("-" + digits) || key.replaceAll("[^0-9]", "").equals(digits)) {
          return key;
        }
      }
    }
    return clean;
  }

  public boolean isGroupExists(int group) {
    return isGroupExists(String.valueOf(group));
  }

  public boolean isGroupExists(String group) {
    if (group == null || group.isBlank()) return false;
    String resolved = resolveGroupKey(group);
    return scheduleCache.containsKey(resolved) || rawLessonsCache.containsKey(resolved);
  }

  public List<List<String>> getLessons(int groupNum, Day day) {
    return getLessons(String.valueOf(groupNum), day);
  }

  public List<List<String>> getLessons(String groupNum, Day day) {
    if (groupNum == null || day == null) return Collections.emptyList();
    String resolved = resolveGroupKey(groupNum);
    Map<Day, List<List<String>>> days = rawLessonsCache.get(resolved);
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

    String resolved = resolveGroupKey(groupNum);
    Map<Day, String> groupDays = scheduleCache.get(resolved);
    if (groupDays == null) {
      return MessagesConfig.GROUP_NOT_FOUND_EXCEPTION;
    }

    return groupDays.getOrDefault(day, MessagesConfig.NO_LESSONS_MESSAGE);
  }

  String[][] buildMatrix(Element table) {
    List<Element> trs = new ArrayList<>();
    for (Element tr : table.select("tr")) {
      if (tr.parent() == table
          || (tr.parent() != null
              && tr.parent().parent() == table
              && (tr.parent().tagName().equalsIgnoreCase("tbody")
                  || tr.parent().tagName().equalsIgnoreCase("thead")
                  || tr.parent().tagName().equalsIgnoreCase("tfoot")))) {
        trs.add(tr);
      }
    }

    List<List<String>> normalized = ru.azenizzka.utils.HtmlTableNormalizer.normalize(table);
    int rowCount = normalized.size();
    int colCount = normalized.isEmpty() ? 0 : normalized.get(0).size();

    String[][] grid = new String[rowCount][colCount];
    for (int r = 0; r < rowCount; r++) {
      List<String> row = normalized.get(r);
      for (int c = 0; c < colCount; c++) {
        grid[r][c] = row.get(c);
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
    parseMatrix(grid, newScheduleCache, newRawCache, null);
  }

  private int findAnchorRow(String[][] grid) {
    for (int r = 0; r < Math.min(35, grid.length); r++) {
      StringBuilder sb = new StringBuilder();
      for (int c = 0; c < grid[r].length; c++) {
        if (grid[r][c] != null && !grid[r][c].isBlank()) {
          sb.append(grid[r][c]).append(' ');
        }
      }
      String rowText = sb.toString().toLowerCase();
      if ((rowText.contains("н/п") || rowText.contains("№"))
          && (rowText.contains("дисциплин") || rowText.contains("предмет"))
          && (rowText.contains("каб") || rowText.contains("ауд"))) {
        return r;
      }
    }
    return -1;
  }

  private String extractGroupNameAt(String[][] grid, int r, int c, Pattern groupCellPattern) {
    if (r < 0 || r >= grid.length || c < 0 || c >= grid[r].length) {
      return null;
    }
    String cell = grid[r][c];
    if (cell == null || cell.isBlank() || cell.length() > 30 || isDate(cell)) {
      return null;
    }

    Matcher m = groupCellPattern.matcher(cell.trim());
    if (m.find()) {
      String letters = m.group(1).replaceAll("[^А-Яа-яЁёA-Za-z]", "").toUpperCase();
      String digits = m.group(2).replaceAll("[^0-9]", "");
      if (!letters.isEmpty() && !digits.isEmpty() && !isIgnoredGroupPrefix(letters)) {
        return letters + "-" + digits;
      }
    }

    String cur = cell.trim();
    if (cur.matches("\\d{2,3}") && r > 0 && c < grid[r - 1].length && grid[r - 1][c] != null) {
      String top = grid[r - 1][c].trim();
      if (top.matches("^[А-Яа-яЁёA-Za-z]{1,4}$") && !isIgnoredGroupPrefix(top)) {
        String letters = top.replaceAll("[^А-Яа-яЁёA-Za-z]", "").toUpperCase();
        String digits = cur.replaceAll("[^0-9]", "");
        if (!letters.isEmpty() && !digits.isEmpty()) {
          return letters + "-" + digits;
        }
      }
    }

    if (cur.matches("^[А-Яа-яЁёA-Za-z]{1,4}$")
        && !isIgnoredGroupPrefix(cur)
        && r + 1 < grid.length
        && c < grid[r + 1].length
        && grid[r + 1][c] != null
        && grid[r + 1][c].trim().matches("\\d{2,3}")) {
      String letters = cur.replaceAll("[^А-Яа-яЁёA-Za-z]", "").toUpperCase();
      String digits = grid[r + 1][c].trim().replaceAll("[^0-9]", "");
      if (!letters.isEmpty() && !digits.isEmpty()) {
        return letters + "-" + digits;
      }
    }

    return null;
  }

  private int resolveStartColumnForGroup(
      String[][] grid, int anchorRow, int foundRow, int col, Map<Integer, String> colToGroup) {
    if (anchorRow >= 0 && anchorRow < grid.length) {
      String[] anchorCells = grid[anchorRow];
      String curAnchor =
          (col < anchorCells.length && anchorCells[col] != null)
              ? anchorCells[col].trim().toLowerCase()
              : "";

      if (curAnchor.contains("н/п") || curAnchor.equals("№")) {
        return col;
      }
      if ((curAnchor.contains("дисциплин") || curAnchor.contains("предмет")) && col - 1 >= 0) {
        String leftAnchor =
            (col - 1 < anchorCells.length && anchorCells[col - 1] != null)
                ? anchorCells[col - 1].trim().toLowerCase()
                : "";
        if ((leftAnchor.contains("н/п") || leftAnchor.equals("№"))
            && !colToGroup.containsKey(col - 1)) {
          return col - 1;
        }
      }
      if ((curAnchor.contains("каб") || curAnchor.contains("ауд")) && col - 2 >= 0) {
        String left2Anchor =
            (col - 2 < anchorCells.length && anchorCells[col - 2] != null)
                ? anchorCells[col - 2].trim().toLowerCase()
                : "";
        if ((left2Anchor.contains("н/п") || left2Anchor.equals("№"))
            && !colToGroup.containsKey(col - 2)) {
          return col - 2;
        }
      }
    }

    int checkRow = anchorRow >= 0 ? anchorRow + 1 : foundRow + 1;
    int prevCol = col - 1;
    if (prevCol >= 0
        && !isColumnPairNumber(grid, checkRow, col)
        && isColumnPairNumber(grid, checkRow, prevCol)
        && !colToGroup.containsKey(prevCol)) {
      return prevCol;
    }
    return col;
  }

  private void parseMatrix(
      String[][] grid,
      Map<String, Map<Day, String>> newScheduleCache,
      Map<String, Map<Day, List<List<String>>>> newRawCache,
      Set<String> expectedGroups) {
    int maxCols = grid.length > 0 ? grid[0].length : 0;
    log.info("Начало разбора матрицы расписания (размер {}x{})...", grid.length, maxCols);
    if (grid.length == 0 || maxCols == 0) {
      return;
    }

    Pattern groupCellPattern = Pattern.compile("([А-Яа-яЁёA-Za-z]+)[\\s-]*(\\d{2,3})(?!\\d)");
    int anchorRow = findAnchorRow(grid);
    int maxHeaderRow = anchorRow > 0 ? anchorRow - 1 : Math.min(15, grid.length - 1);
    int maxGroupRowFound = -1;
    Map<Integer, String> colToGroup = new LinkedHashMap<>();

    // Поколоночное сканирование шапки от строки над якорем (anchorRow - 1) вверх до 0
    for (int c = 2; c < maxCols; c++) {
      String foundGroup = null;
      int foundRow = -1;

      for (int r = maxHeaderRow; r >= 0; r--) {
        String candidate = extractGroupNameAt(grid, r, c, groupCellPattern);
        if (candidate != null) {
          foundGroup = candidate;
          foundRow = r;
          break;
        }
      }

      if (foundGroup != null && !colToGroup.containsValue(foundGroup)) {
        int startCol = resolveStartColumnForGroup(grid, anchorRow, foundRow, c, colToGroup);
        colToGroup.put(startCol, foundGroup);
        newScheduleCache.computeIfAbsent(foundGroup, k -> new HashMap<>());
        newRawCache.computeIfAbsent(foundGroup, k -> new HashMap<>());
        maxGroupRowFound = Math.max(maxGroupRowFound, foundRow);
      }
    }

    if (colToGroup.isEmpty()) {
      log.warn("Группы не найдены в шапке таблицы (anchorRow={})", anchorRow);
      return;
    }

    int scheduleStartIndex =
        anchorRow != -1 ? anchorRow + 1 : (maxGroupRowFound >= 0 ? maxGroupRowFound + 1 : 4);
    log.info(
        "Колонки групп успешно определены (всего {} групп, anchorRow={}, scheduleStartIndex={}): {}",
        colToGroup.size(),
        anchorRow,
        scheduleStartIndex,
        colToGroup);

    int firstGroupCol = colToGroup.keySet().stream().min(Integer::compareTo).orElse(2);
    Collection<Integer> groupCols = colToGroup.keySet();
    int totalLessonsExtracted = 0;

    if (anchorRow != -1 && anchorRow + 36 < grid.length) {
      // Основной режим по эталону vibebunker (pandas_parser4fn.py):
      // жесткие смещения дней недели относительно якоря (ПН=+1, ВТ=+7, СР=+13, ЧТ=+19, ПТ=+25,
      // СБ=+31)
      for (Map.Entry<Integer, String> entry : colToGroup.entrySet()) {
        int startCol = entry.getKey();
        String groupNum = entry.getValue();

        newScheduleCache.computeIfAbsent(groupNum, k -> new HashMap<>());
        Map<Day, String> dayMap = newScheduleCache.get(groupNum);

        newRawCache.computeIfAbsent(groupNum, k -> new HashMap<>());
        Map<Day, List<List<String>>> rawDayMap = newRawCache.get(groupNum);

        for (Day day : STUDY_DAYS) {
          int startRow = anchorRow + DAY_OFFSETS.get(day);
          String prevRoom = null;
          String prevSubject = null;

          for (int r = startRow; r < Math.min(startRow + 6, grid.length); r++) {
            String rawPair =
                (startCol < grid[r].length && grid[r][startCol] != null)
                    ? sanitizeMarkdown(grid[r][startCol])
                    : "";
            String discipline =
                (startCol + 1 < grid[r].length && grid[r][startCol + 1] != null)
                    ? sanitizeMarkdown(grid[r][startCol + 1])
                    : "";
            String room =
                (startCol + 2 < grid[r].length && grid[r][startCol + 2] != null)
                    ? sanitizeMarkdown(grid[r][startCol + 2])
                    : "";

            String rowPair = findRowPairNumber(grid, r, firstGroupCol, groupCols);
            boolean isPairDigit = PAIR_DIGIT_PATTERN.matcher(pairNumOrEmpty(rawPair)).matches();
            String pairNum = rawPair;

            if (!isPairDigit && !rawPair.isBlank() && discipline.isBlank()) {
              discipline = rawPair;
              pairNum = rowPair;
            } else if (!isPairDigit
                && !rawPair.isBlank()
                && room.isBlank()
                && discipline.length() <= 6) {
              room = discipline;
              discipline = rawPair;
              pairNum = rowPair;
            } else if (rawPair.isBlank()) {
              pairNum = rowPair;
            } else {
              pairNum = rawPair.replaceAll("[^0-9]", "");
            }

            if (!isValidDiscipline(discipline)) {
              continue;
            }

            if (pairNum.isBlank()) {
              int inferredPair = r - startRow + 1;
              pairNum =
                  (inferredPair >= 1 && inferredPair <= 6) ? String.valueOf(inferredPair) : "0";
            }

            if (room.isBlank()) {
              if (prevRoom != null && prevSubject != null && prevSubject.equals(discipline)) {
                room = prevRoom;
              }
            } else {
              prevRoom = room;
              prevSubject = discipline;
            }

            List<List<String>> dayLessons = rawDayMap.computeIfAbsent(day, k -> new ArrayList<>());
            dayLessons.add(List.of(pairNum, discipline, room));
            totalLessonsExtracted++;

            String currentSchedule = dayMap.getOrDefault(day, "");
            String lessonLine =
                String.format(
                    "%s. %s%s\n", pairNum, discipline, room.isBlank() ? "" : " (" + room + ")");
            dayMap.put(day, currentSchedule + lessonLine);
          }
        }
      }
    } else {
      // Резервный режим, если таблица нестандартной структуры без якоря
      Map<Integer, Day> rowToDay = new HashMap<>();
      Day currentDay = null;
      int firstDayRow = -1;
      Day firstDetectedDay = null;
      int currentDayRowCount = 0;

      for (int r = scheduleStartIndex; r < grid.length; r++) {
        Day rowExplicitDay = null;
        for (int c = 0; c < Math.min(firstGroupCol, grid[r].length); c++) {
          Day parsed = parseDay(grid[r][c]);
          if (parsed != null) {
            rowExplicitDay = parsed;
            break;
          }
        }

        String rowPair = findRowPairNumber(grid, r, firstGroupCol, groupCols);

        if (rowExplicitDay != null) {
          if (currentDay == null || rowExplicitDay != currentDay) {
            currentDay = rowExplicitDay;
            currentDayRowCount = 0;
            if (firstDetectedDay == null) {
              firstDetectedDay = currentDay;
              firstDayRow = r;
            }
          }
        } else if (currentDay != null && currentDayRowCount >= 4 && "1".equals(rowPair)) {
          Day nextDay = getNextStudyDay(currentDay);
          if (nextDay != null) {
            currentDay = nextDay;
            currentDayRowCount = 0;
          }
        } else if (currentDay == null && firstDetectedDay == null && "1".equals(rowPair)) {
          currentDay = Day.MONDAY;
          firstDetectedDay = currentDay;
          firstDayRow = r;
          currentDayRowCount = 0;
        }

        if (currentDay != null) {
          rowToDay.put(r, currentDay);
          currentDayRowCount++;
        }
      }

      if (firstDayRow > scheduleStartIndex && firstDetectedDay != null) {
        Day prevDay =
            firstDetectedDay == Day.MONDAY ? Day.MONDAY : getPreviousStudyDay(firstDetectedDay);
        if (prevDay != null) {
          for (int r = scheduleStartIndex; r < firstDayRow; r++) {
            rowToDay.put(r, prevDay);
          }
        }
      }

      for (int r = scheduleStartIndex; r < grid.length; r++) {
        Day currentDayRow = rowToDay.get(r);
        if (currentDayRow == null) continue;

        String rowPair = findRowPairNumber(grid, r, firstGroupCol, groupCols);

        for (Map.Entry<Integer, String> entry : colToGroup.entrySet()) {
          int gCol = entry.getKey();
          String groupNum = entry.getValue();

          String pairNum =
              (gCol < grid[r].length && grid[r][gCol] != null)
                  ? sanitizeMarkdown(grid[r][gCol])
                  : "";
          String discipline =
              (gCol + 1 < grid[r].length && grid[r][gCol + 1] != null)
                  ? sanitizeMarkdown(grid[r][gCol + 1])
                  : "";
          String room =
              (gCol + 2 < grid[r].length && grid[r][gCol + 2] != null)
                  ? sanitizeMarkdown(grid[r][gCol + 2])
                  : "";

          boolean isPairDigit = PAIR_DIGIT_PATTERN.matcher(pairNumOrEmpty(pairNum)).matches();
          if (!isPairDigit && !pairNum.isBlank() && discipline.isBlank()) {
            discipline = pairNum;
            pairNum = rowPair;
          } else if (!isPairDigit
              && !pairNum.isBlank()
              && room.isBlank()
              && discipline.length() <= 6) {
            room = discipline;
            discipline = pairNum;
            pairNum = rowPair;
          } else if (pairNum.isBlank()) {
            pairNum = rowPair;
          } else {
            pairNum = pairNum.replaceAll("[^0-9]", "");
          }

          if (isValidDiscipline(discipline)) {
            newScheduleCache.computeIfAbsent(groupNum, k -> new HashMap<>());
            Map<Day, String> dayMap = newScheduleCache.get(groupNum);

            newRawCache.computeIfAbsent(groupNum, k -> new HashMap<>());
            Map<Day, List<List<String>>> rawDayMap = newRawCache.get(groupNum);
            List<List<String>> dayLessons =
                rawDayMap.computeIfAbsent(currentDayRow, k -> new ArrayList<>());

            if (pairNum.isBlank()) {
              pairNum = "0";
            }

            if (room.isBlank() && !dayLessons.isEmpty()) {
              List<String> prevLesson = dayLessons.get(dayLessons.size() - 1);
              if (prevLesson.get(1).equalsIgnoreCase(discipline)
                  && prevLesson.size() > 2
                  && !prevLesson.get(2).isBlank()) {
                room = prevLesson.get(2);
              }
            }

            List<String> lessonTuple = List.of(pairNum, discipline, room);
            if (!"0".equals(pairNum) && dayLessons.contains(lessonTuple)) {
              continue;
            }
            dayLessons.add(lessonTuple);
            totalLessonsExtracted++;

            String currentSchedule = dayMap.getOrDefault(currentDayRow, "");
            String lessonLine =
                String.format(
                    "%s. %s%s\n", pairNum, discipline, room.isBlank() ? "" : " (" + room + ")");
            dayMap.put(currentDayRow, currentSchedule + lessonLine);
          }
        }
      }
    }

    log.info(
        "Разбор матрицы завершен: извлечено {} занятий для {} групп",
        totalLessonsExtracted,
        colToGroup.size());
  }

  private String pairNumOrEmpty(String text) {
    return text == null ? "" : text.trim();
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

  private boolean isValidDiscipline(String discipline) {
    if (discipline == null || discipline.isBlank()) {
      return false;
    }
    String lower = discipline.toLowerCase();
    return !lower.equals("н/п")
        && !lower.equals("№")
        && !lower.equals("каб")
        && !lower.equals("каб.")
        && !lower.startsWith("дисциплин")
        && !lower.startsWith("предмет")
        && !lower.startsWith("кабинет")
        && !lower.startsWith("аудитор");
  }

  private String findRowPairNumber(
      String[][] grid, int r, int maxCol, Collection<Integer> groupCols) {
    for (int c = 0; c < Math.min(maxCol, grid[r].length); c++) {
      String val = grid[r][c];
      if (val != null) {
        String t = val.trim();
        if (PAIR_DIGIT_PATTERN.matcher(t).matches()) {
          return t.replaceAll("[^0-9]", "").substring(0, 1);
        }
      }
    }
    if (groupCols != null) {
      for (int gCol : groupCols) {
        if (gCol >= 0 && gCol < grid[r].length && grid[r][gCol] != null) {
          String t = grid[r][gCol].trim();
          if (PAIR_DIGIT_PATTERN.matcher(t).matches()) {
            return t.replaceAll("[^0-9]", "").substring(0, 1);
          }
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
