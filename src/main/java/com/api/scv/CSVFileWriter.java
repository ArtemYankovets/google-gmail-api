package com.api.scv;

import com.api.model.MailMessage;
import org.supercsv.cellprocessor.constraint.NotNull;
import org.supercsv.cellprocessor.ift.CellProcessor;
import org.supercsv.io.CsvBeanWriter;
import org.supercsv.io.ICsvBeanWriter;
import org.supercsv.prefs.CsvPreference;

import java.io.FileWriter;
import java.io.IOException;
import java.util.List;

public class CSVFileWriter {

    public static void writeCSVFile(String csvFileName, List<MailMessage> listBooks) {
        ICsvBeanWriter beanWriter = null;
        CellProcessor[] processors = new CellProcessor[] {
                new NotNull(), // id
                new NotNull(), // date
                new NotNull(), // from
                new NotNull(), // to
                new NotNull(), // subject
                new NotNull(), // snippet
                new NotNull()  // attachments
        };

        try {
            beanWriter = new CsvBeanWriter(new FileWriter(csvFileName),
                    CsvPreference.STANDARD_PREFERENCE);
            String[] header = {"id", "date", "from", "to", "subject", "snippet", "attachments"};
            beanWriter.writeHeader(header);

            for (MailMessage aBook : listBooks) {
                beanWriter.write(aBook, header, processors);
            }

        } catch (IOException ex) {
            System.err.println("Error writing the CSV file: " + ex);
        } finally {
            if (beanWriter != null) {
                try {
                    beanWriter.close();
                } catch (IOException ex) {
                    System.err.println("Error closing the writer: " + ex);
                }
            }
        }
    }
}
