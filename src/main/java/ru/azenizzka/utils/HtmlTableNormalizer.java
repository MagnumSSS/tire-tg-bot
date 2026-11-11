package ru.azenizzka.utils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

/**
 * Утилитный класс для нормализации сырой HTML-таблицы (раскрутка rowspan и colspan). Эмулирует
 * поведение pandas.read_html, преобразуя объединенные ячейки в прямоугольную двумерную матрицу
 * List&lt;List&lt;String&gt;&gt; с дублированием значений.
 */
public final class HtmlTableNormalizer {

  private static final Pattern VML_COMMENT_PATTERN =
      Pattern.compile(
          "<!--\\[if[^\\]]*\\]>.*?<!\\[endif\\]-->", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
  private static final Pattern VGLAYOUT_SPAN_PATTERN =
      Pattern.compile(
          "<span[^>]*vglayout[^>]*>.*?</span>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
  private static final Pattern BROKEN_VML_TD_PATTERN =
      Pattern.compile(
          "(<td\\b[^>]*style='[^']*?)<!--\\[if[^\\]]*\\]>.*?<!\\[endif\\]-->",
          Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
  private static final Pattern UNCLOSED_STYLE_BEFORE_TAG_PATTERN =
      Pattern.compile(
          "(<td\\b[^>]*style='[^'>]*?)(?=\\s*</?(?:td|tr|table)\\b)", Pattern.CASE_INSENSITIVE);

  private HtmlTableNormalizer() {}

  /**
   * Очищает специфичный HTML-код Excel-выгрузок от VML-тегов и незакрытых стилей, сохраняя
   * целостность многострочных ячеек &lt;td&gt; по аналогии с pandas.read_html.
   *
   * @param rawHtml исходный HTML-документ или фрагмент.
   * @return нормализованный Jsoup Document.
   */
  public static Document parseCleanHtml(String rawHtml) {
    if (rawHtml == null || rawHtml.isBlank()) {
      return Jsoup.parse("<table></table>");
    }

    String cleaned = BROKEN_VML_TD_PATTERN.matcher(rawHtml).replaceAll("$1'></td>");
    cleaned = VML_COMMENT_PATTERN.matcher(cleaned).replaceAll("");
    cleaned = VGLAYOUT_SPAN_PATTERN.matcher(cleaned).replaceAll("");
    cleaned = UNCLOSED_STYLE_BEFORE_TAG_PATTERN.matcher(cleaned).replaceAll("$1'>");

    if (!cleaned.toLowerCase().contains("<table")) {
      cleaned = "<table>\n" + cleaned + "\n</table>";
    }
    return Jsoup.parse(cleaned);
  }

  /**
   * Преобразует сырой HTML в развернутую двумерную матрицу.
   *
   * @param rawHtml сырой HTML выгрузки.
   * @return двумерная матрица строк без rowspan/colspan.
   */
  public static List<List<String>> normalize(String rawHtml) {
    Document doc = parseCleanHtml(rawHtml);
    Element table = doc.selectFirst("table");
    return table != null ? normalize(table) : List.of();
  }

  /**
   * Преобразует элемент &lt;table&gt; в нормализованную двумерную матрицу, дублируя ячейки по
   * горизонтали и вертикали в соответствии с colspan и rowspan.
   *
   * @param table Jsoup-элемент таблицы.
   * @return нормализованная матрица строк List&lt;List&lt;String&gt;&gt;.
   */
  public static List<List<String>> normalize(Element table) {
    if (table == null) {
      return List.of();
    }

    List<Element> trElements = new ArrayList<>();
    for (Element tr : table.select("tr")) {
      Element parent = tr.parent();
      if (parent == table
          || (parent != null
              && parent.parent() == table
              && (parent.tagName().equalsIgnoreCase("tbody")
                  || parent.tagName().equalsIgnoreCase("thead")
                  || parent.tagName().equalsIgnoreCase("tfoot")))) {
        trElements.add(tr);
      }
    }

    if (trElements.isEmpty()) {
      return List.of();
    }

    int rowCount = trElements.size();
    List<List<String>> grid = new ArrayList<>(rowCount);
    for (int i = 0; i < rowCount; i++) {
      grid.add(new ArrayList<>());
    }

    for (int r = 0; r < rowCount; r++) {
      Element tr = trElements.get(r);
      Elements cells = tr.children();
      int currentCol = 0;
      List<String> currentRow = grid.get(r);

      for (Element cell : cells) {
        if (!cell.tagName().equalsIgnoreCase("td") && !cell.tagName().equalsIgnoreCase("th")) {
          continue;
        }

        while (currentCol < currentRow.size() && currentRow.get(currentCol) != null) {
          currentCol++;
        }

        int rowspan = 1;
        int colspan = 1;

        try {
          if (cell.hasAttr("rowspan")) {
            rowspan = Math.max(1, Integer.parseInt(cell.attr("rowspan").trim()));
          }
        } catch (NumberFormatException ignored) {
        }

        try {
          if (cell.hasAttr("colspan")) {
            colspan = Math.max(1, Integer.parseInt(cell.attr("colspan").trim()));
          }
        } catch (NumberFormatException ignored) {
        }

        String text = cell.text().replace("\u00A0", " ").trim();

        for (int dr = 0; dr < rowspan && (r + dr) < rowCount; dr++) {
          List<String> targetRow = grid.get(r + dr);
          while (targetRow.size() < currentCol + colspan) {
            targetRow.add(null);
          }
          for (int dc = 0; dc < colspan; dc++) {
            targetRow.set(currentCol + dc, text);
          }
        }

        currentCol += colspan;
      }
    }

    int maxCols = grid.stream().mapToInt(List::size).max().orElse(0);

    return grid.stream()
        .map(
            row -> {
              List<String> normalizedRow = new ArrayList<>(maxCols);
              for (int col = 0; col < maxCols; col++) {
                String val = col < row.size() ? row.get(col) : "";
                normalizedRow.add(val != null ? val : "");
              }
              return Collections.unmodifiableList(normalizedRow);
            })
        .toList();
  }
}
