/////////////////////////////////////////////////////////////////////////////
//
// Project ProjectForge Community Edition
//         www.projectforge.org
//
// Copyright (C) 2001-2013 Kai Reinhard (k.reinhard@micromata.de)
//
// ProjectForge is dual-licensed.
//
// This community edition is free software; you can redistribute it and/or
// modify it under the terms of the GNU General Public License as published
// by the Free Software Foundation; version 3 of the License.
//
// This community edition is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General
// Public License for more details.
//
// You should have received a copy of the GNU General Public License along
// with this program; if not, see http://www.gnu.org/licenses/.
//
/////////////////////////////////////////////////////////////////////////////

package org.projectforge.plugins.liquidityplanning;

import java.io.Serializable;
import java.math.BigDecimal;
import java.sql.Date;
import java.util.Calendar;

import org.projectforge.calendar.DayHolder;
import org.projectforge.common.DateFormatType;
import org.projectforge.common.DateFormats;
import org.projectforge.common.NumberHelper;
import org.projectforge.excel.ContentProvider;
import org.projectforge.excel.ExportColumn;
import org.projectforge.excel.ExportSheet;
import org.projectforge.excel.I18nExportColumn;
import org.projectforge.excel.PropertyMapping;
import org.projectforge.export.MyExcelExporter;
import org.projectforge.scripting.I18n;

/**
 * @author Kai Reinhard (k.reinhard@micromata.de)
 * 
 */
public class LiquidityForecastCashFlow implements Serializable
{
  private static final long serialVersionUID = 7567091917817930061L;

  private final BigDecimal[] credits;

  private final BigDecimal[] debits;

  private final BigDecimal[] creditsExpected;

  private final BigDecimal[] debitsExpected;

  private final DayHolder today;

  public LiquidityForecastCashFlow(final LiquidityForecast forecast)
  {
    this(forecast, 90);
  }

  public LiquidityForecastCashFlow(final LiquidityForecast forecast, final int nextDays)
  {
    today = new DayHolder();
    final DayHolder lastDay = new DayHolder();
    lastDay.add(Calendar.DAY_OF_YEAR, nextDays);
    credits = newBigDecimalArray(nextDays);
    debits = newBigDecimalArray(nextDays);
    creditsExpected = newBigDecimalArray(nextDays);
    debitsExpected = newBigDecimalArray(nextDays);
    for (final LiquidityEntry entry : forecast.getEntries()) {
      final BigDecimal amount = entry.getAmount();
      if (amount == null) {
        continue;
      }
      final Date dateOfPayment = entry.getDateOfPayment();
      Date expectedDateOfPayment = entry.getExpectedDateOfPayment();
      if (expectedDateOfPayment == null) {
        expectedDateOfPayment = dateOfPayment;
      }
      int numberOfDay = 0;
      if (dateOfPayment != null) {
        if (today.before(dateOfPayment) == true && today.isSameDay(dateOfPayment) == false) {
          numberOfDay = today.daysBetween(dateOfPayment);
        }
      }
      if (numberOfDay >= 0 && numberOfDay < nextDays == true) {
        if (amount.compareTo(BigDecimal.ZERO) == 0) {
          // Zero, nothing to do.
        } else if (amount.compareTo(BigDecimal.ZERO) > 0) {
          debits[numberOfDay] = debits[numberOfDay].add(amount);
        } else {
          credits[numberOfDay] = credits[numberOfDay].add(amount);
        }
      }
      int numberOfDayExpected = 0;
      if (expectedDateOfPayment != null) {
        if (today.before(expectedDateOfPayment) == true && today.isSameDay(expectedDateOfPayment) == false) {
          numberOfDayExpected = today.daysBetween(expectedDateOfPayment);
        }
      }
      if (numberOfDayExpected >= 0 && numberOfDayExpected < nextDays == true) {
        if (amount.compareTo(BigDecimal.ZERO) == 0) {
          // Zero, nothing to do.
        } else if (amount.compareTo(BigDecimal.ZERO) > 0) {
          debitsExpected[numberOfDayExpected] = debitsExpected[numberOfDayExpected].add(amount);
        } else {
          creditsExpected[numberOfDayExpected] = creditsExpected[numberOfDayExpected].add(amount);
        }
      }
    }
  }

  private BigDecimal[] newBigDecimalArray(final int length)
  {
    final BigDecimal[] array = new BigDecimal[length];
    for (int i = 0; i < length; i++) {
      array[i] = BigDecimal.ZERO;
    }
    return array;
  }

  public void addAsExcelSheet(final MyExcelExporter exporter, final String sheetTitle)
  {
    final ExportSheet sheet = exporter.addSheet(sheetTitle);
    sheet.createFreezePane(0, 1);
    final ContentProvider sheetProvider = sheet.getContentProvider();

    sheet.addRow();
    sheet.setMergedRegion(0, 0, 1, 2, I18n.getString("plugins.liquidityplanning.entry.expectedDateOfPayment"));
    sheet.setMergedRegion(0, 0, 3, 4, I18n.getString("plugins.liquidityplanning.forecast.dueDate"));

    final ExportColumn[] cols = new ExportColumn[5];
    int colNo = 0;
    I18nExportColumn exportColumn = new I18nExportColumn("date", "date", 10);
    sheetProvider.putFormat(exportColumn, DateFormats.getExcelFormatString(DateFormatType.DATE));
    cols[colNo++] = exportColumn;

    exportColumn = new I18nExportColumn("creditsExpected", "plugins.liquidityplanning.common.credit");
    cols[colNo++] = exportColumn;
    exporter.putCurrencyFormat(sheetProvider, exportColumn);
    exportColumn.setWidth(15);

    exportColumn = new I18nExportColumn("debitsExpected", "plugins.liquidityplanning.common.debit");
    cols[colNo++] = exportColumn;
    exporter.putCurrencyFormat(sheetProvider, exportColumn);
    exportColumn.setWidth(15);

    exportColumn = new I18nExportColumn("credits", "plugins.liquidityplanning.common.credit");
    cols[colNo++] = exportColumn;
    exporter.putCurrencyFormat(sheetProvider, exportColumn);
    exportColumn.setWidth(15);

    exportColumn = new I18nExportColumn("debits", "plugins.liquidityplanning.common.debit");
    cols[colNo++] = exportColumn;
    exporter.putCurrencyFormat(sheetProvider, exportColumn);
    exportColumn.setWidth(15);
    // column property names
    sheet.setColumns(cols);

    final DayHolder current = today.clone();
    final PropertyMapping mapping = new PropertyMapping();
    for (int i = 0; i < credits.length; i++) {
      mapping.add("date", current);
      mapping.add("creditsExpected", NumberHelper.isZeroOrNull(creditsExpected[i]) == true ? "" : creditsExpected[i]);
      mapping.add("debitsExpected", NumberHelper.isZeroOrNull(debitsExpected[i]) == true ? "" : debitsExpected[i]);
      mapping.add("credits", NumberHelper.isZeroOrNull(credits[i]) == true ? "" : credits[i]);
      mapping.add("debits", NumberHelper.isZeroOrNull(debits[i]) == true ? "" : debits[i]);
      sheet.addRow(mapping.getMapping(), 0);
      current.add(Calendar.DAY_OF_YEAR, 1);
    }
  }

  /**
   * @return the credits based on due dates.
   */
  public BigDecimal[] getCredits()
  {
    return credits;
  }

  /**
   * @return the creditsExpected based on expected dates of payment.
   */
  public BigDecimal[] getCreditsExpected()
  {
    return creditsExpected;
  }

  /**
   * @return the debits based on due dates.
   */
  public BigDecimal[] getDebits()
  {
    return debits;
  }

  /**
   * @return the debitsExpected based on expected dates of payment.
   */
  public BigDecimal[] getDebitsExpected()
  {
    return debitsExpected;
  }
}
