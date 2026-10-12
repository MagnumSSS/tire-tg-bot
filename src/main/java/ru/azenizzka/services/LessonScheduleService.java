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
  private static final Pattern GROUP_PATTERN = Pattern.compile("(?<!\\d)\\d{2,3}(?!\\d)");

  private final Map<String, Map<Day, String>> scheduleCache = new ConcurrentHashMap<>();
  private final Map<String, Map<Day, List<List<String>>>> rawLessonsCache = new ConcurrentHashMap<>();

  @PostConstruct
  public void init() {
    updateCache();
  }

  @Scheduled(fixedRate = 10 * 60 * 1000)
  public void scheduledCacheUpdate() {
    updateCache();
  }

  // Ежедневно в 08:00 по Новосибирску (ПН-СБ) — расписание на СЕГОДНЯ в групповые чаты с закрепом
  @Scheduled(cron = "0 0 8 * * MON-SAT", zone = "Asia/Novosibirsk")
  public void sendMorningGroupSchedules() {
    try {
      Day today = DayUtil.convertStrToDay("сегодня");
      broadcastPinnedToGroups(today, "сегодня (" + formatDayRus(today) + ")");
    } catch (Exception e) {
      log.error("Ошибка утренней рассылки в группы: {}", e.getMessage());
    }
  }

  // Ежедневно в 18:00 по Новосибирску (ПН-СБ) — расписание на ЗАВТРА в групповые чаты с закрепом
  @Scheduled(cron = "0 0 18 * * MON-SAT", zone = "Asia/Novosibirsk")
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

    CompletableFuture.runAsync(() -> {
      for (Person p : personService.findAll()) {
        if (p.getChatId() != null
            && p.getChatId().startsWith("-")
            && !p.getChatId().equals(botConfiguration.getAuditLogChatId())
            && p.getGroupNum() != 0
            && p.isNotificationsEnabled()
            && !p.isBanned()) {
          String text = buildDetailedScheduleText(p.getGroupNum(), day, label);
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

  public String buildDetailedScheduleText(int groupNum, Day day, String label) {
    List<List<String>> lessons = getLessons(groupNum, day);
    if (lessons.isEmpty()) {
      return "📌 *Расписание " + groupNum + " группы на " + label + "*\n\n" + MessagesConfig.NO_LESSONS_MESSAGE;
    }

    StringBuilder result = new StringBuilder("📌 *Расписание " + groupNum + " группы*\n*" + label + "*\n\n");
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
        result.append("⏱ `").append(bellTime).append("`\n");
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

  private List<String> discoverSheetUrls() {
    Set<String> urls = new LinkedHashSet<>();
    try {
      Document indexDoc = Jsoup.connect("https://ntmm.ru/student/raspisanie.php")
          .sslSocketFactory(SSL_SOCKET_FACTORY)
          .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
          .timeout(15_000)
          .get();

      for (Element a : indexDoc.select("a[href]")) {
        String href = a.attr("href").trim();
        if (href.contains("/incoming/R-OO/") && (href.endsWith(".htm") || href.endsWith(".html"))) {
          String fullUrl = href.startsWith("http")
              ? href
              : "https://ntmm.ru" + (href.startsWith("/") ? "" : "/") + href;
          String sheetUrl = fullUrl.replaceAll("\\.html?$", ".files/sheet001.htm");
          urls.add(sheetUrl);
        }
      }
    } catch (Exception e) {
      log.warn("Не удалось получить список ссылок с raspisanie.php: {}", e.getMessage());
    }

    if (urls.isEmpty()) {
      String baseUrl = "https://ntmm.ru/incoming/R-OO/";
      for (int i = 1; i <= 8; i++) {
        if (i == 2) {
          urls.add(baseUrl + "2_screen1.files/sheet001.htm");
        }
        urls.add(baseUrl + i + "_screen.files/sheet001.htm");
      }
    }

    return new ArrayList<>(urls);
  }

  public synchronized void updateCache() {
    log.info("Запуск обновления кэша расписания...");

    Map<String, Map<Day, String>> newScheduleCache = new HashMap<>();
    Map<String, Map<Day, List<List<String>>>> newRawCache = new HashMap<>();

    List<String> sheetUrls = discoverSheetUrls();

    for (String url : sheetUrls) {
      try {
        Document doc = Jsoup.connect(url)
            .sslSocketFactory(SSL_SOCKET_FACTORY)
            .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
            .timeout(15_000)
            .get();

        Element table = doc.selectFirst("table");
        if (table == null) {
          log.warn("Таблица расписания не найдена по адресу {}", url);
          continue;
        }

        String[][] grid = buildMatrix(table);
        parseMatrix(grid, newScheduleCache, newRawCache);
        log.info("Успешно спарсили {}", url);

      } catch (Exception e) {
        log.error("Ошибка при обновлении кэша с {}: {}", url, e.getMessage());
      }
    }

    if (!newScheduleCache.isEmpty()) {
      Map<String, Map<Day, String>> oldCacheSnapshot = new HashMap<>(scheduleCache);
      boolean wasInitialized = !oldCacheSnapshot.isEmpty();

      scheduleCache.clear();
      scheduleCache.putAll(newScheduleCache);

      rawLessonsCache.clear();
      rawLessonsCache.putAll(newRawCache);

      if (wasInitialized) {
        checkAndNotifyChanges(newScheduleCache, oldCacheSnapshot);
      }

      log.info("Кэш успешно обновлен. Загружено групп: {}", scheduleCache.size());
    } else {
      log.warn("Не удалось загрузить расписание ни с одной страницы!");
    }
  }

  private void checkAndNotifyChanges(Map<String, Map<Day, String>> newScheduleCache, Map<String, Map<Day, String>> oldScheduleCache) {
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

      for (Map.Entry<Day, String> dayEntry : newDays.entrySet()) {
        Day day = dayEntry.getKey();
        String newText = dayEntry.getValue().trim();
        String oldText = oldDays.getOrDefault(day, "").trim();

        if (!newText.equals(oldText)) {
          notifyStudentsOnScheduleChange(groupNum, day, newText);
        }
      }
    }
  }

  public void notifyStudentsOnScheduleChange(int groupNum, Day day, String newScheduleText) {
    String dayRus = formatDayRus(day);

    List<SendMessage> notifications = new ArrayList<>();
    for (Person p : personService.findAll()) {
      if (p.getGroupNum() == groupNum && p.isNotificationsEnabled() && !p.isBanned()) {
        if (p.getChatId() != null && p.getChatId().startsWith("-")) {
          if (TelegramBot.getInstance() != null && !p.getChatId().equals(botConfiguration.getAuditLogChatId())) {
            String pinText = "🔔 *Обновление расписания!*\n\n" + buildDetailedScheduleText(groupNum, day, dayRus);
            TelegramBot.getInstance().sendAndPinSchedule(p, pinText);
          }
        } else {
          SendMessage msg = new SendMessage(
              p.getChatId(),
              "🔔 *Обнаружено изменение в расписании!*\n"
                  + "🎓 Группа: *" + groupNum + "*\n"
                  + "📅 День: *" + dayRus + "*\n\n"
                  + "*Новое расписание:*\n"
                  + newScheduleText);
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
    int colCount = 100;

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

        int rowspan = cell.hasAttr("rowspan") ? Integer.parseInt(cell.attr("rowspan")) : 1;
        int colspan = cell.hasAttr("colspan") ? Integer.parseInt(cell.attr("colspan")) : 1;
        String text = cell.text().replace("\u00A0", " ").trim();

        for (int dr = 0; dr < rowspan && (r + dr) < rowCount; dr++) {
          for (int dc = 0; dc < colspan && (c + dc) < colCount; dc++) {
            grid[r + dr][c + dc] = text;
          }
        }
        c += colspan;
      }
    }
    return grid;
  }

  private void parseMatrix(String[][] grid, Map<String, Map<Day, String>> newScheduleCache,
                           Map<String, Map<Day, List<List<String>>>> newRawCache) {
    int groupRowIndex = -1;
    for (int r = 0; r < Math.min(10, grid.length); r++) {
      int groupMatches = 0;
      for (int c = 0; c < grid[r].length; c++) {
        if (grid[r][c] != null && GROUP_PATTERN.matcher(grid[r][c]).find()) {
          groupMatches++;
        }
      }
      if (groupMatches >= 3) {
        groupRowIndex = r;
        break;
      }
    }

    if (groupRowIndex == -1) {
      log.warn("Строка с номерами групп не найдена");
      return;
    }

    Map<Integer, String> colToGroup = new HashMap<>();
    for (int c = 0; c < grid[groupRowIndex].length; c++) {
      String cell = grid[groupRowIndex][c];
      if (cell != null && !cell.isBlank()) {
        Matcher m = GROUP_PATTERN.matcher(cell);
        if (m.find()) {
          String groupNum = m.group();
          if (!colToGroup.containsValue(groupNum)) {
            colToGroup.put(c, groupNum);
          }
        }
      }
    }

    List<List<Integer>> dayBlocks = new ArrayList<>();
    List<Integer> currentBlock = null;

    for (int r = groupRowIndex + 1; r < grid.length; r++) {
      boolean isDayStart = false;
      for (int c = 0; c < Math.min(4, grid[r].length); c++) {
        String val = grid[r][c];
        if (val != null && val.trim().equals("1")) {
          isDayStart = true;
          break;
        }
      }

      if (isDayStart) {
        currentBlock = new ArrayList<>();
        dayBlocks.add(currentBlock);
      }

      if (currentBlock != null) {
        currentBlock.add(r);
      }
    }

    Day[] defaultDays = {Day.MONDAY, Day.TUESDAY, Day.WEDNESDAY, Day.THURSDAY, Day.FRIDAY, Day.SATURDAY};
    Map<Integer, Day> rowToDay = new HashMap<>();

    for (int i = 0; i < dayBlocks.size(); i++) {
      List<Integer> block = dayBlocks.get(i);
      Day blockDay = (i < defaultDays.length) ? defaultDays[i] : null;

      for (int row : block) {
        for (int c = 0; c < Math.min(5, grid[row].length); c++) {
          Day parsed = parseDay(grid[row][c]);
          if (parsed != null) {
            blockDay = parsed;
            break;
          }
        }
        if (blockDay != null && i < defaultDays.length && blockDay == defaultDays[i]) {
          break;
        }
      }

      if (blockDay != null) {
        for (int row : block) {
          rowToDay.put(row, blockDay);
        }
      }
    }

    for (int r = groupRowIndex + 1; r < grid.length; r++) {
      Day currentDay = rowToDay.get(r);
      if (currentDay == null) continue;

      for (Map.Entry<Integer, String> entry : colToGroup.entrySet()) {
        int gCol = entry.getKey();
        String groupNum = entry.getValue();

        String pairNum = (gCol < grid[r].length && grid[r][gCol] != null) ? grid[r][gCol] : "";
        String discipline = (gCol + 1 < grid[r].length && grid[r][gCol + 1] != null) ? grid[r][gCol + 1] : "";
        String room = (gCol + 2 < grid[r].length && grid[r][gCol + 2] != null) ? grid[r][gCol + 2] : "";

        if (!discipline.isBlank() && !discipline.equalsIgnoreCase("н/п") && !discipline.equalsIgnoreCase("Дисциплина")) {
          newScheduleCache.computeIfAbsent(groupNum, k -> new HashMap<>());
          Map<Day, String> dayMap = newScheduleCache.get(groupNum);

          String currentSchedule = dayMap.getOrDefault(currentDay, "");
          String lessonLine = String.format("%s. %s %s\n",
              pairNum.isBlank() ? "•" : pairNum,
              discipline,
              room.isBlank() ? "" : "(" + room + ")");
          dayMap.put(currentDay, currentSchedule + lessonLine);

          newRawCache.computeIfAbsent(groupNum, k -> new HashMap<>());
          Map<Day, List<List<String>>> rawDayMap = newRawCache.get(groupNum);
          rawDayMap.computeIfAbsent(currentDay, k -> new ArrayList<>());
          rawDayMap.get(currentDay).add(List.of(pairNum, discipline, room));
        }
      }
    }
  }

  private Day parseDay(String text) {
    if (text == null) return null;
    String t = text.replace("\u00A0", " ").trim().toUpperCase();
    if (t.contains("ПОНЕДЕЛЬНИК") || t.equals("ПН")) return Day.MONDAY;
    if (t.contains("ВТОРНИК") || t.equals("ВТ")) return Day.TUESDAY;
    if (t.contains("СРЕДА") || t.equals("СР")) return Day.WEDNESDAY;
    if (t.contains("ЧЕТВЕРГ") || t.equals("ЧТ")) return Day.THURSDAY;
    if (t.contains("ПЯТНИЦА") || t.equals("ПТ")) return Day.FRIDAY;
    if (t.contains("СУББОТА") || t.equals("СБ")) return Day.SATURDAY;
    return null;
  }

  private static SSLSocketFactory createInsecureSslSocketFactory() {
    TrustManager[] trustAllCerts = new TrustManager[] {
      new X509TrustManager() {
        public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
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
