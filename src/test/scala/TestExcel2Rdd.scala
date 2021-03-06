
import org.scalatest.FunSuite
import org.scalatest.Matchers
import org.scalatest.Tag

import scala.reflect.ClassTag
import scala.io.Source

import java.io.{BufferedReader, File, FileNotFoundException}

import org.apache.commons.io.FileUtils

import org.apache.spark.{SparkConf, SparkContext}
import org.apache.spark.mllib.linalg.{Vector => LinAlgVector}
import org.apache.spark.rdd.RDD

import org.apache.poi.POIXMLException

import excel2rdd.Excel2RDD
import excel2rdd.{ExcelRowFilter, ExcelHeaderDiscard, ExcelHeaderExtract, ExcelNoHeader}
import excel2rdd.{ExcelColumnFilter, ExcelDropColumns, ExcelColumnIdentity}
import excel2rdd.ExcelRowIdentity


object TagExcelObviousErrors extends Tag("private.tags.ExcelObviousErrors")
object TagExcelUtilityFunctions extends Tag("private.tags.ExcelUtilityFunctions")

object TagExcelFilteringFunc extends Tag("private.tags.Excel.Filter")

object TagExcelDoFilterHeader extends Tag("private.tags.Excel.Filter.Header.DoFilter")
object TagExcelNoFilterHeader extends Tag("private.tags.Excel.Filter.Header.NoFilter")

object TagExcelNoFilterColumns extends Tag("private.tags.Excel.Filter.Columns.NoFilter")
object TagExcelDoFilter1stColm extends Tag("private.tags.Excel.Filter.Columns.Filter1s")
object TagExcelDoFilter2ndColm extends Tag("private.tags.Excel.Filter.Columns.Filter2d")
object TagExcelDoFilter2t4Cols extends Tag("private.tags.Excel.Filter.Columns.Filter2t4")
object TagExcelDoFilterLastColm extends Tag("private.tags.Excel.Filter.Columns.FilterLast")


class TestExcel2Rdd extends FunSuite with Matchers {

  // This is the sample Excel XLSX

  val sampleExcelXlsx = "/sample_excel.xlsx"

  // The right spreadsheet in our sample Excel file

  val rightSpreadshTab = "test"

  // The right values of the header row in our sample Excel file

  val rightSpreadshHeader = Array("headerCol1", "headerCol2", "headerCol3", "headerCol4",
                                  "headerCol5", "headerCol6", "headerCol7")

  // The directory under which to save the RDD as a CSV

  val saveRddToCsvDir = "/tmp/tempDirectory"      // TODO: do it in a portable manner, and for
                              // parallel test execution (disabled in the "build.sbt" SBT file,
                              // http://www.scala-sbt.org/0.13/docs/Parallel-Execution.html

  // the filename with the real CSV file saved from the RDD

  val realCsvFromRdd = saveRddToCsvDir + "/part-00000"   // using rdd.repartition(1)...: see below

  lazy val sparkContext = {

    val sparkConf = new SparkConf().setMaster("local[2]").setAppName("Test")
    sparkConf.set("spark.ui.enabled", "false")

    val sc = new SparkContext(sparkConf)
    info("Just created the Apache Spark Context.")

    sc
  }

  def saveRdd2Csv(rdd: RDD[LinAlgVector], outputBaseDir: String): Unit = {

    FileUtils.deleteQuietly(new File(outputBaseDir))

    rdd.repartition(1).saveAsTextFile(outputBaseDir)
    info("Done saving the parsed Spark RDD back to a single CSV file.")
  }

  def compareCsvData(realCsvResults: String, expectedCsvResults: String): Boolean = {

    info(s"Comparing contents of '$realCsvResults' with the expected '$expectedCsvResults'.")

    val realLines = Source.fromFile(realCsvResults).getLines
    val expectedLines =
      Source.fromInputStream(getClass.getResourceAsStream(expectedCsvResults)).getLines

    // compare the two BufferedLineIterator's

    realLines.zip(expectedLines).forall(pair => pair._1 == pair._2) &&
      (realLines.length == expectedLines.length)

  }

  test("Converting an unenxisting Excel XLSX to CSV", TagExcelObviousErrors) {

    val nonExistingXlsx = File.createTempFile("non-existing-excel-file-", ".xlsx")
    val excelXlsx = new Excel2RDD(nonExistingXlsx.getAbsolutePath)

    // This message given in the Apache POI exception may change in the future
    val expectedMsg =
      "org.apache.poi.openxml4j.exceptions.InvalidFormatException: Package should contain a content type part [M1.13]"

    the [POIXMLException] thrownBy { excelXlsx.open() } should have message expectedMsg
  }

  test("Converting an unenxisting spreadsheet in an existing Excel XLSX to CSV",
       TagExcelObviousErrors) {
    val sampleExcel = getClass.getResourceAsStream(sampleExcelXlsx)
    val excelXlsx = new Excel2RDD(sampleExcel)

    excelXlsx.open()
    val wrongSpreadshTab = rightSpreadshTab + "_a_surpluous_wrong_suffix"    // make it wrong
    intercept[NullPointerException] {
      val parsedData = excelXlsx.convertExcelSpreadsh2RDD(wrongSpreadshTab, ExcelHeaderExtract,
                                                          ExcelColumnIdentity, ExcelRowIdentity,
                                                          sparkContext)
    }
    excelXlsx.close()
  }


  def stdTestExcel2RddWithFilters(rowFilter: ExcelRowFilter, columnFilter: ExcelColumnFilter,
                                  expectedRddAsCsvResult: String,
                                  checkState: Excel2RDD => Boolean = Function.const(true)):
      Boolean = {
    val sampleExcel = getClass.getResourceAsStream(sampleExcelXlsx)
    val excelXlsx = new Excel2RDD(sampleExcel)

    excelXlsx.open()
    val parsedData = excelXlsx.convertExcelSpreadsh2RDD(rightSpreadshTab, rowFilter, columnFilter,
                                                        ExcelRowIdentity, sparkContext)
    excelXlsx.close()
    info("Done ETL of the input Excel spreadsheet to an Apache Spark RDD.")

    val intermediateCsvFName = excelXlsx.getCsvFileName
    val intermCsvFile = new java.io.File(intermediateCsvFName)
    val intermCsvExists = intermCsvFile.exists
    val intermCsvSize = intermCsvFile.length
    info("Checked intermediate CSV file obtained from the Excel spreadsheet.")

    // in the general case, the size of the intermediate csv file doesn't need to be > 0 if the
    // input Excel spreadsheet exists but is empty
    if (!intermCsvExists || intermCsvSize == 0) {
      false
    } else {

      saveRdd2Csv(parsedData, saveRddToCsvDir)

      if (compareCsvData(realCsvFromRdd, expectedRddAsCsvResult)) {
        // the comparison of the contents from the realCsvFromRdd is the same as the expected
        // contents in expectedRddAsCsvResult. Check the state of the object itself
        checkState(excelXlsx)
      } else {
        // the CSV contents from the actual Spark RDD and the expected contents were different
        false
      }
    }
  }

  test("Converting an Excel XLSX to CSV, zero filtering of data",
       TagExcelNoFilterHeader, TagExcelNoFilterColumns, TagExcelFilteringFunc) {

    val res = stdTestExcel2RddWithFilters(ExcelNoHeader, ExcelColumnIdentity,
                                          "/parsed_sample_excel_with_header_all_cols.csv")

    res should equal (true)
  }

  test("Converting an Excel XLSX to CSV, filtering out header row only",
       TagExcelDoFilterHeader, TagExcelNoFilterColumns, TagExcelFilteringFunc) {

    val res = stdTestExcel2RddWithFilters(ExcelHeaderDiscard, ExcelColumnIdentity,
                                          "/parsed_sample_excel_no_header_all_cols.csv")

    res should equal (true)
  }

  test("Converting an Excel XLSX to CSV, filtering out first column only",
       TagExcelNoFilterHeader, TagExcelDoFilter1stColm, TagExcelFilteringFunc) {

    val res = stdTestExcel2RddWithFilters(ExcelNoHeader, new ExcelDropColumns(Array(0)),
                                          "/parsed_sample_excel_with_header_no_1st_col.csv")

    res should equal (true)
  }

  test("Converting an Excel XLSX to CSV, filtering out second column only",
       TagExcelNoFilterHeader, TagExcelDoFilter2ndColm, TagExcelFilteringFunc) {

    val res = stdTestExcel2RddWithFilters(ExcelNoHeader, new ExcelDropColumns(Array(1)),
                                          "/parsed_sample_excel_with_header_no_2nd_col.csv")

    res should equal (true)
  }

  test("Converting an Excel XLSX to CSV, filtering out second to fourth columns only",
       TagExcelNoFilterHeader, TagExcelDoFilter2t4Cols, TagExcelFilteringFunc) {

    val res = stdTestExcel2RddWithFilters(ExcelNoHeader, new ExcelDropColumns(Array(1, 2, 3)),
                                          "/parsed_sample_excel_with_header_no_2nd_to_4th_cols.csv")

    res should equal (true)
  }

  test("Converting an Excel XLSX to CSV, filtering out last column only",
       TagExcelNoFilterHeader, TagExcelDoFilterLastColm, TagExcelFilteringFunc) {

    val res = stdTestExcel2RddWithFilters(ExcelNoHeader, new ExcelDropColumns(Array(6)),
                                          "/parsed_sample_excel_with_header_no_last_col.csv")

    res should equal (true)
  }

  test("Converting an Excel XLSX to CSV, filtering out header row and first column",
       TagExcelDoFilterHeader, TagExcelDoFilter1stColm, TagExcelFilteringFunc) {

    val res = stdTestExcel2RddWithFilters(ExcelHeaderDiscard, new ExcelDropColumns(Array(0)),
                                          "/parsed_sample_excel_no_header_no_1st_col.csv")

    res should equal (true)
  }

  test("Converting an Excel XLSX to CSV, filtering out header row and second column",
       TagExcelDoFilterHeader, TagExcelDoFilter2ndColm, TagExcelFilteringFunc) {

    val res = stdTestExcel2RddWithFilters(ExcelHeaderDiscard, new ExcelDropColumns(Array(1)),
                                          "/parsed_sample_excel_no_header_no_2nd_col.csv")

    res should equal (true)
  }

  test("Converting an Excel XLSX to CSV, filtering out header row and second to fourth columns",
       TagExcelDoFilterHeader, TagExcelDoFilter2t4Cols, TagExcelFilteringFunc) {

    val res = stdTestExcel2RddWithFilters(ExcelHeaderDiscard,
                                          new ExcelDropColumns(Array(1, 2, 3)),
                                          "/parsed_sample_excel_no_header_no_2nd_to_4th_cols.csv")

    res should equal (true)
  }

  test("Converting an Excel XLSX to CSV, filtering out header row and last column",
       TagExcelDoFilterHeader, TagExcelDoFilterLastColm, TagExcelFilteringFunc) {

    val res = stdTestExcel2RddWithFilters(ExcelHeaderDiscard, new ExcelDropColumns(Array(6)),
                                          "/parsed_sample_excel_no_header_no_last_col.csv")

    res should equal (true)
  }

  def removeIndices[T:ClassTag](indicesToDrop: Seq[Int], originalSeq: Seq[T]): Seq[T] = {
    originalSeq.indices.diff(indicesToDrop).map( { case (idx) => originalSeq(idx) } )
  }

  test("Converting an Excel XLSX to CSV, filtering out header row only and saving it internally",
       TagExcelDoFilterHeader, TagExcelNoFilterColumns, TagExcelFilteringFunc) {

    val res = stdTestExcel2RddWithFilters(ExcelHeaderExtract, ExcelColumnIdentity,
                                          "/parsed_sample_excel_no_header_all_cols.csv",
                                          (e: Excel2RDD) => {
                                             rightSpreadshHeader.zipWithIndex forall {
                                               case (expectedHdrVal, hrdIdx) =>
                                                 e.getHeader(hrdIdx) == expectedHdrVal
                                             }
                                          })

    res should equal (true)
  }

  test("Converting an Excel XLSX to CSV, filtering out header row and first column" +
       ", and saving header internally",
       TagExcelDoFilterHeader, TagExcelDoFilter1stColm, TagExcelFilteringFunc) {

    val dropCols = Array(0)
    val res = stdTestExcel2RddWithFilters(ExcelHeaderExtract, new ExcelDropColumns(dropCols),
                                          "/parsed_sample_excel_no_header_no_1st_col.csv",
                                          (e: Excel2RDD) => {
                                             removeIndices(dropCols, rightSpreadshHeader).
                                               zipWithIndex forall {
                                                 case (expectedHdrVal, hrdIdx) =>
                                                   e.getHeader(hrdIdx) == expectedHdrVal
                                               }
                                          })

    res should equal (true)
  }

  test("Converting an Excel XLSX to CSV, filtering out header row and second column" +
       ", and saving header internally",
       TagExcelDoFilterHeader, TagExcelDoFilter2ndColm, TagExcelFilteringFunc) {

    val dropCols = Array(1)
    val res = stdTestExcel2RddWithFilters(ExcelHeaderExtract, new ExcelDropColumns(dropCols),
                                          "/parsed_sample_excel_no_header_no_2nd_col.csv",
                                          (e: Excel2RDD) => {
                                             removeIndices(dropCols, rightSpreadshHeader).
                                               zipWithIndex forall {
                                                 case (expectedHdrVal, hrdIdx) =>
                                                   e.getHeader(hrdIdx) == expectedHdrVal
                                               }
                                          })

    res should equal (true)
  }

  test("Converting an Excel XLSX to CSV, filtering out header row and second to fourth columns" +
       ", and saving header internally",
       TagExcelDoFilterHeader, TagExcelDoFilter2t4Cols, TagExcelFilteringFunc) {

    val dropCols = Array(1, 2, 3)
    val res = stdTestExcel2RddWithFilters(ExcelHeaderExtract, new ExcelDropColumns(dropCols),
                                          "/parsed_sample_excel_no_header_no_2nd_to_4th_cols.csv",
                                          (e: Excel2RDD) => {
                                             removeIndices(dropCols, rightSpreadshHeader).
                                               zipWithIndex forall {
                                                 case (expectedHdrVal, hrdIdx) =>
                                                   e.getHeader(hrdIdx) == expectedHdrVal
                                               }
                                          })

    res should equal (true)
  }

  test("Converting an Excel XLSX to CSV, filtering out header row and last column" +
       ", and saving header internally",
       TagExcelDoFilterHeader, TagExcelDoFilterLastColm, TagExcelFilteringFunc) {

    val dropCols = Array(6)
    val res = stdTestExcel2RddWithFilters(ExcelHeaderExtract, new ExcelDropColumns(dropCols),
                                          "/parsed_sample_excel_no_header_no_last_col.csv",
                                          (e: Excel2RDD) => {
                                             removeIndices(dropCols, rightSpreadshHeader).
                                               zipWithIndex forall {
                                                 case (expectedHdrVal, hrdIdx) =>
                                                   e.getHeader(hrdIdx) == expectedHdrVal
                                               }
                                          })

    res should equal (true)
  }

  val rangeToTest: Seq[Int] = Seq.range(0, 50)

  test("Check the removeIndices() function itself: removing even indices, leaving odd ones",
       TagExcelUtilityFunctions) {
    val allIndices = rangeToTest.toArray
    val evenIndices = rangeToTest.filter(_ % 2 == 0).toArray
    val oddIndices = rangeToTest.filterNot(_ % 2 == 0).toArray

    val remainingIndices = removeIndices(evenIndices, allIndices)
    val res = remainingIndices.sameElements(oddIndices)

    res should equal (true)
  }

  test("Check the removeIndices() function itself: removing odd indices, leaving even ones",
       TagExcelUtilityFunctions) {
    val allIndices = rangeToTest.toArray
    val evenIndices = rangeToTest.filter(_ % 2 == 0).toArray
    val oddIndices = rangeToTest.filterNot(_ % 2 == 0).toArray

    val remainingIndices = removeIndices(oddIndices, allIndices)
    val res = remainingIndices.sameElements(evenIndices)

    res should equal (true)
  }

  test("Check the removeIndices() function itself: removing indices '(i - 1) % 3 == 0', " +
       "leaving all the other indices", TagExcelUtilityFunctions) {
    val allIndices = rangeToTest.toArray
    val selector = (i: Int) => { (i - 1) % 3 == 0 }
    val indicesToDrop = rangeToTest.filter(selector).toArray
    val expectedRemaining = rangeToTest.filterNot(selector).toArray

    val remainingIndices = removeIndices(indicesToDrop, allIndices)
    val res = remainingIndices.sameElements(expectedRemaining)

    res should equal (true)
  }

}

