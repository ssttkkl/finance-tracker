import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFCell;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** Offline fixture writer. Input is already identity-anonymized, never a personal bill. */
public class CashFixtureWorkbook {
    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        var options = new LoaderOptions();
        options.setCodePointLimit(8 * 1024 * 1024);
        Map<String, Object> input = new Yaml(new SafeConstructor(options))
                .load(new String(System.in.readAllBytes(), StandardCharsets.UTF_8));
        try (Workbook book = args[0].equals("xls") ? new HSSFWorkbook() : new XSSFWorkbook()) {
            var sheet = book.createSheet("账单");
            int index = 0;
            for (String line : (List<String>) input.get("metadata")) {
                sheet.createRow(index++).createCell(0).setCellValue(line);
            }
            List<String> headers = (List<String>) input.get("headers");
            var heading = sheet.createRow(index++);
            for (int column = 0; column < headers.size(); column++) {
                heading.createCell(column).setCellValue(headers.get(column));
            }
            for (Map<String, String> values : (List<Map<String, String>>) input.get("rows")) {
                var row = sheet.createRow(index++);
                for (int column = 0; column < headers.size(); column++) {
                    String value = values.get(headers.get(column));
                    var cell = row.createCell(column);
                    cell.setCellValue(value);
                    // A legitimate inline-string workbook also retains the >64 KiB XML regression.
                    if (cell instanceof XSSFCell xssf) {
                        xssf.getCTCell().setT(org.openxmlformats.schemas.spreadsheetml.x2006.main.STCellType.INLINE_STR);
                        xssf.getCTCell().unsetV();
                        xssf.getCTCell().addNewIs().setT(value);
                    }
                }
            }
            if (book instanceof HSSFWorkbook hssf) {
                hssf.createInformationProperties();
                hssf.getSummaryInformation().setAuthor("Finance Tracker QA");
                hssf.getSummaryInformation().setApplicationName("Finance Tracker QA");
            } else if (book instanceof XSSFWorkbook xssf) {
                xssf.getProperties().getCoreProperties().setCreator("Finance Tracker QA");
                xssf.getProperties().getCoreProperties().setLastModifiedByUser("Finance Tracker QA");
            }
            var output = new ByteArrayOutputStream();
            book.write(output);
            Files.write(Path.of(args[1]), output.toByteArray());
        }
    }
}
