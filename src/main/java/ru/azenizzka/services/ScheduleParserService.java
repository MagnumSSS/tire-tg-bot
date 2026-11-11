package ru.azenizzka.services;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Service;
import ru.azenizzka.dto.DaySchedule;
import ru.azenizzka.dto.Group;
import ru.azenizzka.dto.Lesson;
import ru.azenizzka.utils.Day;
import ru.azenizzka.utils.HtmlTableNormalizer;

/**
 * Сервис парсинга расписания колледжа из HTML-выгрузок Excel (ntmm.ru). Разворачивает объединенные
 * ячейки таблицы и преобразует матрицу в строго типизированные DTO.
 */
@Service
@Slf4j
public class ScheduleParserService {

  private static final Pattern GROUP_FULL_PATTERN =
      Pattern.compile("([А-Яа-яЁёA-Za-z]+)[\\s-]*(\\d{2,3})(?!\\d)");
  private static final Pattern GROUP_DIGITS_ONLY_PATTERN = Pattern.compile("^\\d{2,3}$");
  private static final Pattern GROUP_LETTERS_ONLY_PATTERN =
      Pattern.compile("^[А-Яа-яЁёA-Za-z]{1,4}$");
  private static final Pattern PAIR_DIGIT_PATTERN =
      Pattern.compile("^[0-6](\\s*(пара|п\\.?|\\.)|-[1-6])?$");
  private static final Pattern REPLACEMENT_DETECT_PATTERN =
      Pattern.compile("(?i).*(замен[а-я]*|зам\\.|вместо).*");
  private static final Pattern REPLACEMENT_CLEAN_PATTERN =
      Pattern.compile("(?i)\\b(замен[а-я]*|зам\\.|вместо)\\b");
  private static final Pattern SUBGROUP_1_PATTERN =
      Pattern.compile(
          "(?i)(1\\s*(п/?г|подгрупп[а-я]*|пг)|(?:п/?г|подгрупп[а-я]*|пг)\\s*1|\\bI\\s*(п/?г|подгрупп[а-я]*|пг))");
  private static final Pattern SUBGROUP_2_PATTERN =
      Pattern.compile(
          "(?i)(2\\s*(п/?г|подгрупп[а-я]*|пг)|(?:п/?г|подгрупп[а-я]*|пг)\\s*2|\\bII\\s*(п/?г|подгрупп[а-я]*|пг))");
  private static final Pattern TEACHER_PATTERN =
      Pattern.compile("([А-Яа-яЁё]+)\\s+([А-ЯЁ]\\.[\\s]?[А-ЯЁ]\\.?)");
  private static final Pattern WHITESPACE_PATTERN = Pattern.compile("\\s+");

  private static final Map<Day, Integer> DAY_OFFSETS =
      Map.of(
          Day.MONDAY, 1,
          Day.TUESDAY, 7,
          Day.WEDNESDAY, 13,
          Day.THURSDAY, 19,
          Day.FRIDAY, 25,
          Day.SATURDAY, 31);

  private static final List<Day> STUDY_DAYS_ORDER =
      List.of(Day.MONDAY, Day.TUESDAY, Day.WEDNESDAY, Day.THURSDAY, Day.FRIDAY, Day.SATURDAY);

  private record GroupColumn(int columnIndex, Group group, int foundRow) {}

  /**
   * Полный парсинг сырой HTML-страницы в список DaySchedule.
   *
   * @param rawHtml сырая HTML-страница расписания.
   * @return список DaySchedule для всех найденных групп и дней недели.
   */
  public List<DaySchedule> parseSchedule(String rawHtml) {
    if (rawHtml == null || rawHtml.isBlank()) {
      return List.of();
    }
    List<List<String>> grid = HtmlTableNormalizer.normalize(rawHtml);
    return parseGrid(grid);
  }

  /**
   * Парсинг Jsoup-элемента таблицы в список DaySchedule.
   *
   * @param table тег &lt;table&gt;.
   * @return список DaySchedule.
   */
  public List<DaySchedule> parseSchedule(Element table) {
    if (table == null) {
      return List.of();
    }
    List<List<String>> grid = HtmlTableNormalizer.normalize(table);
    return parseGrid(grid);
  }

  /**
   * Парсинг расписания только для одной конкретной группы.
   *
   * @param rawHtml сырая HTML-страница.
   * @param groupName наименование или номер группы (например, "С-111" или "111").
   * @return список DaySchedule для запрошенной группы.
   */
  public List<DaySchedule> parseScheduleForGroup(String rawHtml, String groupName) {
    if (groupName == null || groupName.isBlank()) {
      return List.of();
    }
    String cleanTarget = groupName.trim().toUpperCase();
    String digitsTarget = cleanTarget.replaceAll("[^0-9]", "");

    return parseSchedule(rawHtml).stream()
        .filter(
            ds -> {
              String name = ds.group().name().toUpperCase();
              return name.equalsIgnoreCase(cleanTarget)
                  || (!digitsTarget.isEmpty()
                      && (name.endsWith("-" + digitsTarget)
                          || name.replaceAll("[^0-9]", "").equals(digitsTarget)));
            })
        .toList();
  }

  /**
   * Извлечение всех доступных групп из HTML-расписания.
   *
   * @param rawHtml сырой HTML.
   * @return список уникальных объектов Group.
   */
  public List<Group> parseGroups(String rawHtml) {
    List<List<String>> grid = HtmlTableNormalizer.normalize(rawHtml);
    if (grid.isEmpty()) {
      return List.of();
    }
    int anchorRow = findAnchorRowIndex(grid);
    return extractGroupColumns(grid, anchorRow).stream()
        .map(GroupColumn::group)
        .distinct()
        .toList();
  }

  /**
   * Разбор нормализованной сетки в объекты расписания по алгоритму эталона vibebunker (якорная
   * строка + смещения дней недели + поколоночное сканирование групп).
   */
  public List<DaySchedule> parseGrid(List<List<String>> grid) {
    if (grid == null || grid.isEmpty()) {
      return List.of();
    }

    int anchorRow = findAnchorRowIndex(grid);
    List<GroupColumn> groupColumns = extractGroupColumns(grid, anchorRow);
    if (groupColumns.isEmpty()) {
      log.warn("Не удалось извлечь ни одной группы из таблицы (anchorRow={})", anchorRow);
      return List.of();
    }

    int firstGroupCol = groupColumns.stream().mapToInt(GroupColumn::columnIndex).min().orElse(2);
    Set<Integer> groupColIndices =
        groupColumns.stream()
            .map(GroupColumn::columnIndex)
            .collect(HashSet::new, Set::add, Set::addAll);

    Map<Group, Map<Day, List<Lesson>>> scheduleMap = new LinkedHashMap<>();
    for (GroupColumn gc : groupColumns) {
      Map<Day, List<Lesson>> dayMap = new LinkedHashMap<>();
      for (Day day : STUDY_DAYS_ORDER) {
        dayMap.put(day, new ArrayList<>());
      }
      scheduleMap.put(gc.group(), dayMap);
    }

    if (anchorRow != -1 && anchorRow + 36 < grid.size()) {
      // Основной режим по эталону vibebunker: строгие смещения относительно anchorRow
      for (GroupColumn groupCol : groupColumns) {
        int startCol = groupCol.columnIndex();
        Group group = groupCol.group();

        for (Day day : STUDY_DAYS_ORDER) {
          int startRow = anchorRow + DAY_OFFSETS.get(day);
          String prevRoom = null;
          String prevSubject = null;

          for (int r = startRow; r < Math.min(startRow + 6, grid.size()); r++) {
            List<String> row = grid.get(r);
            String rawPair = startCol < row.size() ? row.get(startCol) : "";
            String rawSubject = (startCol + 1) < row.size() ? row.get(startCol + 1) : "";
            String rawRoom = (startCol + 2) < row.size() ? row.get(startCol + 2) : "";

            String fallbackRowPair = findRowPairNumber(grid, r, firstGroupCol, groupColIndices);
            Lesson parsedLesson =
                buildLessonFromCells(
                    rawPair,
                    rawSubject,
                    rawRoom,
                    fallbackRowPair,
                    r - startRow + 1,
                    prevRoom,
                    prevSubject);

            if (parsedLesson == null) {
              continue;
            }

            if (rawRoom != null && !rawRoom.isBlank()) {
              prevRoom = rawRoom.trim();
              prevSubject = rawSubject.trim();
            }

            scheduleMap.get(group).get(day).add(parsedLesson);
          }
        }
      }
    } else {
      // Резервный режим, если таблица нестандартной высоты или без явно выраженного якоря
      int scheduleStartRow =
          anchorRow != -1
              ? anchorRow + 1
              : groupColumns.stream().mapToInt(GroupColumn::foundRow).max().orElse(1) + 1;
      Map<Integer, Day> rowToDay =
          mapRowsToDays(grid, scheduleStartRow, firstGroupCol, groupColIndices);
      Map<String, String> prevRoomMap = new HashMap<>();
      Map<String, String> prevSubjMap = new HashMap<>();

      for (int r = scheduleStartRow; r < grid.size(); r++) {
        Day currentDay = rowToDay.get(r);
        if (currentDay == null) {
          continue;
        }

        List<String> row = grid.get(r);
        String rowPair = findRowPairNumber(grid, r, firstGroupCol, groupColIndices);

        for (GroupColumn groupCol : groupColumns) {
          int gCol = groupCol.columnIndex();
          Group group = groupCol.group();

          String rawPair = gCol < row.size() ? row.get(gCol) : "";
          String rawSubject = (gCol + 1) < row.size() ? row.get(gCol + 1) : "";
          String rawRoom = (gCol + 2) < row.size() ? row.get(gCol + 2) : "";

          String prevKey = group.name() + ":" + currentDay;
          Lesson lesson =
              buildLessonFromCells(
                  rawPair,
                  rawSubject,
                  rawRoom,
                  rowPair,
                  0,
                  prevRoomMap.get(prevKey),
                  prevSubjMap.get(prevKey));

          if (lesson == null) {
            continue;
          }

          if (rawRoom != null && !rawRoom.isBlank()) {
            prevRoomMap.put(prevKey, rawRoom.trim());
            prevSubjMap.put(prevKey, rawSubject.trim());
          }

          scheduleMap
              .computeIfAbsent(group, k -> new LinkedHashMap<>())
              .computeIfAbsent(currentDay, k -> new ArrayList<>())
              .add(lesson);
        }
      }
    }

    List<DaySchedule> result = new ArrayList<>();
    for (Map.Entry<Group, Map<Day, List<Lesson>>> groupEntry : scheduleMap.entrySet()) {
      Group group = groupEntry.getKey();
      for (Map.Entry<Day, List<Lesson>> dayEntry : groupEntry.getValue().entrySet()) {
        if (!dayEntry.getValue().isEmpty()) {
          result.add(new DaySchedule(group, dayEntry.getKey(), dayEntry.getValue()));
        }
      }
    }

    return Collections.unmodifiableList(result);
  }

  private Lesson buildLessonFromCells(
      String rawPair,
      String rawSubject,
      String rawRoom,
      String fallbackRowPair,
      int defaultPairNum,
      String prevRoom,
      String prevSubject) {
    String pairCell = rawPair != null ? rawPair.trim() : "";
    String subjCell = rawSubject != null ? rawSubject.trim() : "";
    String roomCell = rawRoom != null ? rawRoom.trim() : "";

    boolean isPairDigit = PAIR_DIGIT_PATTERN.matcher(pairCell).matches();
    String discipline;
    String room;
    String pairStr;

    if (!isPairDigit && !pairCell.isBlank() && subjCell.isBlank()) {
      discipline = pairCell;
      room = roomCell;
      pairStr = fallbackRowPair;
    } else if (!isPairDigit
        && !pairCell.isBlank()
        && roomCell.isBlank()
        && subjCell.length() <= 6) {
      discipline = pairCell;
      room = subjCell;
      pairStr = fallbackRowPair;
    } else if (pairCell.isBlank()) {
      pairStr = fallbackRowPair;
      discipline = subjCell;
      room = roomCell;
    } else {
      pairStr = pairCell.replaceAll("[^0-9]", "");
      discipline = subjCell;
      room = roomCell;
    }

    if (isEmptyOrGarbagePair(discipline)) {
      return null;
    }

    int pairNum = parsePairNumber(pairStr, defaultPairNum);
    boolean replacement = isReplacement(discipline, room, pairCell);
    Optional<Integer> subgroup = detectSubgroup(discipline);
    Optional<String> teacher = extractTeacher(discipline);
    String cleanSubject = cleanDiscipline(discipline);
    if (cleanSubject.isBlank()) {
      cleanSubject = WHITESPACE_PATTERN.matcher(discipline).replaceAll(" ").trim();
    }

    String finalRoom = room.trim();
    if (finalRoom.isBlank()) {
      if (prevRoom != null
          && !prevRoom.isBlank()
          && prevSubject != null
          && discipline.equalsIgnoreCase(prevSubject.trim())) {
        finalRoom = prevRoom;
      }
    }

    return new Lesson(
        pairNum, cleanSubject, finalRoom, teacher.orElse(""), subgroup.orElse(null), replacement);
  }

  /** Склейка разорванных названий групп, если буквы и цифры разделены по строкам/ячейкам. */
  private Optional<String> glueGroupName(String topCell, String currentCell) {
    if (currentCell == null || currentCell.isBlank()) {
      return Optional.empty();
    }

    String cur = currentCell.trim();
    if (isDate(cur)) {
      return Optional.empty();
    }

    Matcher fullMatcher = GROUP_FULL_PATTERN.matcher(cur);
    if (fullMatcher.find()) {
      String letters = fullMatcher.group(1).replaceAll("[^А-Яа-яЁёA-Za-z]", "").toUpperCase();
      String digits = fullMatcher.group(2).replaceAll("[^0-9]", "");
      if (!letters.isEmpty() && !digits.isEmpty() && !isIgnoredGroupPrefix(letters)) {
        return Optional.of(letters + "-" + digits);
      }
    }

    if (GROUP_DIGITS_ONLY_PATTERN.matcher(cur).matches() && topCell != null && !topCell.isBlank()) {
      String top = topCell.trim();
      if (GROUP_LETTERS_ONLY_PATTERN.matcher(top).matches() && !isIgnoredGroupPrefix(top)) {
        String letters = top.replaceAll("[^А-Яа-яЁёA-Za-z]", "").toUpperCase();
        String digits = cur.replaceAll("[^0-9]", "");
        if (!letters.isEmpty() && !digits.isEmpty()) {
          return Optional.of(letters + "-" + digits);
        }
      }
    }

    return Optional.empty();
  }

  /** Определение замен и изменений в паре. */
  private boolean isReplacement(String subject, String room, String rawCell) {
    return detectReplacementMarker(subject)
        || detectReplacementMarker(room)
        || detectReplacementMarker(rawCell);
  }

  private boolean detectReplacementMarker(String text) {
    return text != null && !text.isBlank() && REPLACEMENT_DETECT_PATTERN.matcher(text).matches();
  }

  /** Определение подгруппы (1, 2). */
  private Optional<Integer> detectSubgroup(String text) {
    if (text == null || text.isBlank()) {
      return Optional.empty();
    }
    if (SUBGROUP_1_PATTERN.matcher(text).find()) {
      return Optional.of(1);
    }
    if (SUBGROUP_2_PATTERN.matcher(text).find()) {
      return Optional.of(2);
    }
    return Optional.empty();
  }

  /** Фильтрация пустых пар, мусорных строк и технических заголовков. */
  private boolean isEmptyOrGarbagePair(String subject) {
    if (subject == null || subject.isBlank()) {
      return true;
    }
    String s = subject.trim();
    if (s.length() <= 3 && s.matches("^[-—._xXхХ…]+$")) {
      return true;
    }
    String lower = s.toLowerCase();
    return lower.equals("нет")
        || lower.equals("нет пар")
        || lower.equals("отсутствует")
        || lower.equals("н/п")
        || lower.equals("№")
        || lower.equals("пара")
        || lower.equals("день")
        || lower.equals("каб")
        || lower.equals("каб.")
        || lower.equals("...")
        || lower.startsWith("дисциплин")
        || lower.startsWith("предмет")
        || lower.startsWith("кабинет")
        || lower.startsWith("аудитор")
        || isDate(s);
  }

  /** Очистка названия предмета от служебных пометок и разметки. */
  private String cleanDiscipline(String subject) {
    if (subject == null) {
      return "";
    }
    String clean = subject.replace("*", "").replace("_", " ").replace("`", "'");
    clean = SUBGROUP_1_PATTERN.matcher(clean).replaceAll("");
    clean = SUBGROUP_2_PATTERN.matcher(clean).replaceAll("");
    clean = REPLACEMENT_CLEAN_PATTERN.matcher(clean).replaceAll("");
    clean = TEACHER_PATTERN.matcher(clean).replaceAll("");
    clean = clean.replaceAll("[()\\[\\]]", " ");
    return WHITESPACE_PATTERN.matcher(clean).replaceAll(" ").trim();
  }

  /** Извлечение преподавателя из поля дисциплины. */
  private Optional<String> extractTeacher(String text) {
    if (text == null || text.isBlank()) {
      return Optional.empty();
    }
    Matcher m = TEACHER_PATTERN.matcher(text);
    if (m.find()) {
      return Optional.of(m.group(0).trim());
    }
    return Optional.empty();
  }

  /** Распознавание дня недели с использованием pattern matching. */
  private Optional<Day> parseDay(String text) {
    if (text == null || text.isBlank()) {
      return Optional.empty();
    }
    String clean =
        text.replace("\u00A0", " ")
            .trim()
            .toUpperCase()
            .replaceAll("[^\\p{L}\\p{Nd}]+", " ")
            .trim();

    for (String word : clean.split("\\s+")) {
      Day matched = null;
      if (word.startsWith("ПОНЕД") || word.equals("ПН")) {
        matched = Day.MONDAY;
      } else if (word.startsWith("ВТОРН") || word.equals("ВТ")) {
        matched = Day.TUESDAY;
      } else if (word.startsWith("СРЕД") || word.equals("СР")) {
        matched = Day.WEDNESDAY;
      } else if (word.startsWith("ЧЕТВ") || word.equals("ЧТ")) {
        matched = Day.THURSDAY;
      } else if (word.startsWith("ПЯТН") || word.equals("ПТ")) {
        matched = Day.FRIDAY;
      } else if (word.startsWith("СУББ") || word.equals("СБ")) {
        matched = Day.SATURDAY;
      }
      if (matched != null) {
        return Optional.of(matched);
      }
    }
    return Optional.empty();
  }

  /**
   * Ищет индекс строки-якоря ("н/п | Дисциплина | каб.") по аналогии с эталоном vibebunker
   * (parser_n1.py / pandas_parser4fn.py).
   */
  public int findAnchorRowIndex(List<List<String>> grid) {
    for (int r = 0; r < Math.min(35, grid.size()); r++) {
      String rowText = String.join(" ", grid.get(r)).toLowerCase();
      if ((rowText.contains("н/п") || rowText.contains("№"))
          && (rowText.contains("дисциплин") || rowText.contains("предмет"))
          && (rowText.contains("каб") || rowText.contains("ауд"))) {
        return r;
      }
    }
    return -1;
  }

  /**
   * Извлекает колонки групп. Сначала проверяет строку непосредственно над якорем (anchorRow - 1),
   * как в vibebunker (`df.iloc[anchor_row - 1, 2:]`), а если из-за кривого объединения ячеек
   * название группы оказалось выше в шапке — сканирует строки шапки от (anchorRow - 1) вверх до 0,
   * выравнивая стартовую колонку по подзаголовку "н/п" в строке-якоре.
   */
  private List<GroupColumn> extractGroupColumns(List<List<String>> grid, int anchorRow) {
    List<GroupColumn> columns = new ArrayList<>();
    Set<String> seenGroups = new HashSet<>();
    int maxCols = grid.stream().mapToInt(List::size).max().orElse(0);

    int maxHeaderRow = anchorRow > 0 ? anchorRow - 1 : Math.min(15, grid.size() - 1);

    for (int c = 2; c < maxCols; c++) {
      String foundGroup = null;
      int foundRow = -1;

      for (int r = maxHeaderRow; r >= 0; r--) {
        List<String> row = grid.get(r);
        String currentCell = c < row.size() ? row.get(c) : "";
        String topCell = (r > 0 && c < grid.get(r - 1).size()) ? grid.get(r - 1).get(c) : "";

        Optional<String> glued = glueGroupName(topCell, currentCell);
        if (glued.isPresent()) {
          foundGroup = glued.get();
          foundRow = r;
          break;
        }
      }

      if (foundGroup != null && seenGroups.add(foundGroup)) {
        int startCol = resolveGroupStartCol(grid, anchorRow, foundRow, c, columns);
        columns.add(new GroupColumn(startCol, new Group(foundGroup), foundRow));
      }
    }
    return columns;
  }

  private int resolveGroupStartCol(
      List<List<String>> grid,
      int anchorRow,
      int foundRow,
      int col,
      List<GroupColumn> existingColumns) {
    if (anchorRow >= 0 && anchorRow < grid.size()) {
      List<String> anchorCells = grid.get(anchorRow);
      String curAnchor = col < anchorCells.size() ? anchorCells.get(col).trim().toLowerCase() : "";

      if (curAnchor.contains("н/п") || curAnchor.equals("№")) {
        return col;
      }
      if ((curAnchor.contains("дисциплин") || curAnchor.contains("предмет")) && col - 1 >= 0) {
        String leftAnchor =
            (col - 1) < anchorCells.size() ? anchorCells.get(col - 1).trim().toLowerCase() : "";
        if ((leftAnchor.contains("н/п") || leftAnchor.equals("№"))
            && existingColumns.stream().noneMatch(gc -> gc.columnIndex() == col - 1)) {
          return col - 1;
        }
      }
      if ((curAnchor.contains("каб") || curAnchor.contains("ауд")) && col - 2 >= 0) {
        String left2Anchor =
            (col - 2) < anchorCells.size() ? anchorCells.get(col - 2).trim().toLowerCase() : "";
        if ((left2Anchor.contains("н/п") || left2Anchor.equals("№"))
            && existingColumns.stream().noneMatch(gc -> gc.columnIndex() == col - 2)) {
          return col - 2;
        }
      }
    }

    int checkRow = anchorRow >= 0 ? anchorRow + 1 : foundRow + 1;
    int prevCol = col - 1;
    if (prevCol >= 0
        && !isColumnPairNumber(grid, checkRow, col)
        && isColumnPairNumber(grid, checkRow, prevCol)
        && existingColumns.stream().noneMatch(gc -> gc.columnIndex() == prevCol)) {
      return prevCol;
    }
    return col;
  }

  private Map<Integer, Day> mapRowsToDays(
      List<List<String>> grid, int startRow, int firstGroupCol, Collection<Integer> groupCols) {
    Map<Integer, Day> rowToDay = new HashMap<>();
    Day currentDay = null;
    int firstDayRow = -1;
    Day firstDetectedDay = null;
    int currentDayRowCount = 0;

    for (int r = startRow; r < grid.size(); r++) {
      List<String> row = grid.get(r);
      Day rowExplicitDay = null;

      for (int c = 0; c < Math.min(firstGroupCol, row.size()); c++) {
        Optional<Day> parsed = parseDay(row.get(c));
        if (parsed.isPresent()) {
          rowExplicitDay = parsed.get();
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

    if (firstDayRow > startRow && firstDetectedDay != null) {
      Day prevDay =
          firstDetectedDay == Day.MONDAY ? Day.MONDAY : getPreviousStudyDay(firstDetectedDay);
      if (prevDay != null) {
        for (int r = startRow; r < firstDayRow; r++) {
          rowToDay.put(r, prevDay);
        }
      }
    }

    // Резервная эмуляция смещений (по аналогии с day_offsets в Python)
    if (rowToDay.isEmpty()) {
      Day[] daysOrder = {
        Day.MONDAY, Day.TUESDAY, Day.WEDNESDAY, Day.THURSDAY, Day.FRIDAY, Day.SATURDAY
      };
      for (int i = 0; i < daysOrder.length; i++) {
        Day day = daysOrder[i];
        int dayStart = startRow + (i * 6);
        for (int r = dayStart; r < Math.min(dayStart + 6, grid.size()); r++) {
          rowToDay.put(r, day);
        }
      }
    }

    return rowToDay;
  }

  private String findRowPairNumber(
      List<List<String>> grid, int r, int maxCol, Collection<Integer> groupCols) {
    List<String> row = grid.get(r);
    for (int c = 0; c < Math.min(maxCol, row.size()); c++) {
      String val = row.get(c);
      if (val != null) {
        String t = val.trim();
        if (PAIR_DIGIT_PATTERN.matcher(t).matches()) {
          return t.replaceAll("[^0-9]", "").substring(0, 1);
        }
      }
    }
    if (groupCols != null) {
      for (int gCol : groupCols) {
        if (gCol >= 0 && gCol < row.size() && row.get(gCol) != null) {
          String t = row.get(gCol).trim();
          if (PAIR_DIGIT_PATTERN.matcher(t).matches()) {
            return t.replaceAll("[^0-9]", "").substring(0, 1);
          }
        }
      }
    }
    return "";
  }

  private boolean isColumnPairNumber(List<List<String>> grid, int startRow, int col) {
    if (col < 0) return false;
    int pairDigits = 0;
    for (int r = startRow; r < Math.min(startRow + 25, grid.size()); r++) {
      List<String> row = grid.get(r);
      if (col >= row.size() || row.get(col) == null) continue;
      String val = row.get(col).trim();
      if (val.equalsIgnoreCase("н/п") || val.equalsIgnoreCase("№")) {
        return true;
      }
      if (val.length() == 1 && val.charAt(0) >= '1' && val.charAt(0) <= '6') {
        pairDigits++;
      }
    }
    return pairDigits >= 2;
  }

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

  private boolean isDate(String cell) {
    if (cell == null || cell.length() < 5) return false;
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

  private int parsePairNumber(String pairStr, int defaultNumber) {
    try {
      String digits = pairStr.replaceAll("[^0-9]", "");
      return digits.isEmpty() ? defaultNumber : Integer.parseInt(digits);
    } catch (NumberFormatException e) {
      return defaultNumber;
    }
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
}
