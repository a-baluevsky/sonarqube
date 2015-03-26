/*
 * SonarQube, open source software quality management tool.
 * Copyright (C) 2008-2014 SonarSource
 * mailto:contact AT sonarsource DOT com
 *
 * SonarQube is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * SonarQube is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.batch.index;

import com.google.common.base.Charsets;
import com.google.common.collect.Lists;
import org.apache.commons.io.FileUtils;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.sonar.api.batch.fs.internal.DefaultFileSystem;
import org.sonar.api.batch.fs.internal.DefaultInputFile;
import org.sonar.api.batch.sensor.duplication.Duplication;
import org.sonar.api.batch.sensor.duplication.internal.DefaultDuplication;
import org.sonar.api.batch.sensor.highlighting.TypeOfText;
import org.sonar.api.measures.CoreMetrics;
import org.sonar.api.measures.Measure;
import org.sonar.api.measures.Metric;
import org.sonar.api.source.Symbol;
import org.sonar.batch.duplication.DuplicationCache;
import org.sonar.batch.highlighting.SyntaxHighlightingData;
import org.sonar.batch.highlighting.SyntaxHighlightingDataBuilder;
import org.sonar.batch.protocol.output.BatchReport.Scm;
import org.sonar.batch.protocol.output.BatchReport.Scm.Changeset;
import org.sonar.batch.protocol.output.BatchReportWriter;
import org.sonar.batch.report.PublishReportJob;
import org.sonar.batch.scan.filesystem.InputFileMetadata;
import org.sonar.batch.scan.measure.MeasureCache;
import org.sonar.batch.source.CodeColorizers;
import org.sonar.batch.symbol.DefaultSymbolTableBuilder;
import org.sonar.core.source.SnapshotDataTypes;
import org.sonar.server.source.db.FileSourceDb;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class SourceDataFactoryTest {

  @Rule
  public TemporaryFolder temp = new TemporaryFolder();

  private MeasureCache measureCache = mock(MeasureCache.class);
  private ComponentDataCache componentDataCache = mock(ComponentDataCache.class);
  private DuplicationCache duplicationCache = mock(DuplicationCache.class);
  private CodeColorizers colorizers = mock(CodeColorizers.class);
  private DefaultInputFile inputFile;
  private InputFileMetadata metadata;
  private SourceDataFactory sut;
  private FileSourceDb.Data.Builder output;
  private File reportDir;

  @Before
  public void setUp() throws Exception {
    PublishReportJob publishReportJob = mock(PublishReportJob.class);
    reportDir = temp.newFolder();
    when(publishReportJob.getReportDir()).thenReturn(reportDir);
    ResourceCache resourceCache = new ResourceCache();
    resourceCache.add(org.sonar.api.resources.File.create("src/Foo.java").setEffectiveKey("module_key:src/Foo.java"), null);
    when(measureCache.byMetric(anyString(), anyString())).thenReturn(Collections.<Measure>emptyList());
    sut = new SourceDataFactory(measureCache, componentDataCache, duplicationCache, colorizers, publishReportJob, resourceCache);
    // generate a file with 3 lines
    File baseDir = temp.newFolder();
    DefaultFileSystem fs = new DefaultFileSystem(baseDir.toPath());
    inputFile = new DefaultInputFile("module_key", "src/Foo.java")
      .setLines(3)
      .setCharset(Charsets.UTF_8);
    fs.add(inputFile);
    metadata = new InputFileMetadata();
    FileUtils.write(inputFile.file(), "one\ntwo\nthree\n");
    output = sut.createForSource(inputFile);
    when(duplicationCache.byComponent(anyString())).thenReturn(Collections.<DefaultDuplication>emptyList());
  }

  @Test
  public void createForSource() throws Exception {
    FileSourceDb.Data data = output.build();
    assertThat(data.getLinesCount()).isEqualTo(3);
    for (int index = 1; index <= 3; index++) {
      assertThat(data.getLines(index - 1).getLine()).isEqualTo(index);
    }
  }

  @Test
  public void consolidateData() throws Exception {
    byte[] bytes = sut.consolidateData(inputFile, metadata);
    assertThat(bytes).isNotEmpty();
  }

  @Test
  public void applyLineMeasure() throws Exception {
    Metric metric = CoreMetrics.COVERAGE_LINE_HITS_DATA;
    when(measureCache.byMetric("component_key", metric.key())).thenReturn(
      // line 1 has 10 hits, ...
      Arrays.asList(new Measure().setData("1=10;3=4").setMetric(metric)));

    sut.applyLineMeasure("component_key", metric.key(), output, new SourceDataFactory.MeasureOperation() {
      @Override
      public void apply(String value, FileSourceDb.Line.Builder lineBuilder) {
        lineBuilder.setUtLineHits(Integer.parseInt(value));
      }
    });

    FileSourceDb.Data data = output.build();
    assertThat(data.getLines(0).getUtLineHits()).isEqualTo(10);
    assertThat(data.getLines(1).hasUtLineHits()).isFalse();
    assertThat(data.getLines(2).getUtLineHits()).isEqualTo(4);
  }

  @Test
  public void applyLineMeasure_ignore_bad_line_numbers() throws Exception {
    Metric metric = CoreMetrics.COVERAGE_LINE_HITS_DATA;
    when(measureCache.byMetric("component_key", metric.key())).thenReturn(
      // line 30 does not exist
      Arrays.asList(new Measure().setData("30=42").setMetric(metric)));

    sut.applyLineMeasure("component_key", metric.key(), output, new SourceDataFactory.MeasureOperation() {
      @Override
      public void apply(String value, FileSourceDb.Line.Builder lineBuilder) {
        lineBuilder.setUtLineHits(Integer.parseInt(value));
      }
    });

    FileSourceDb.Data data = output.build();
    assertThat(data.getLinesCount()).isEqualTo(3);
  }

  @Test
  public void applyScm() throws Exception {
    BatchReportWriter writer = new BatchReportWriter(reportDir);
    writer.writeComponentScm(Scm.newBuilder().setComponentRef(1)
      .addChangeset(Changeset.newBuilder()
        .setRevision("ABC")
        .setAuthor("him")
        .setDate(123456L)
        .build())
      .addChangeset(Changeset.newBuilder()
        .build())
      .addChangesetIndexByLine(0)
      .addChangesetIndexByLine(1)
      .addChangesetIndexByLine(0)
      // This should never happens but here there is 4 data but inputfile has only 3 lines
      .addChangesetIndexByLine(1)
      .build());

    sut.applyScm(inputFile, output);

    FileSourceDb.Data data = output.build();
    assertThat(data.getLines(0).getScmRevision()).isEqualTo("ABC");
    assertThat(data.getLines(0).getScmAuthor()).isEqualTo("him");

    assertThat(data.getLines(1).hasScmRevision()).isFalse();
    assertThat(data.getLines(1).hasScmAuthor()).isFalse();

    assertThat(data.getLines(2).getScmRevision()).isEqualTo("ABC");
    assertThat(data.getLines(2).getScmAuthor()).isEqualTo("him");

  }

  @Test
  public void applyLineMeasures() throws Exception {
    setupLineMeasure(CoreMetrics.COVERAGE_LINE_HITS_DATA, "1=10;3=4");
    setupLineMeasure(CoreMetrics.CONDITIONS_BY_LINE, "1=10;3=4");
    setupLineMeasure(CoreMetrics.CONDITIONS_BY_LINE, "1=10;3=4");
    setupLineMeasure(CoreMetrics.COVERED_CONDITIONS_BY_LINE, "1=10;3=4");
    setupLineMeasure(CoreMetrics.IT_COVERAGE_LINE_HITS_DATA, "1=11;2=4");
    setupLineMeasure(CoreMetrics.IT_COVERED_CONDITIONS_BY_LINE, "1=10;3=4");
    setupLineMeasure(CoreMetrics.IT_CONDITIONS_BY_LINE, "1=10;3=4");
    setupLineMeasure(CoreMetrics.OVERALL_COVERAGE_LINE_HITS_DATA, "1=10;3=4");
    setupLineMeasure(CoreMetrics.OVERALL_CONDITIONS_BY_LINE, "1=10;3=4");
    setupLineMeasure(CoreMetrics.OVERALL_COVERED_CONDITIONS_BY_LINE, "1=10;3=4");

    sut.applyLineMeasures(inputFile, output);

    FileSourceDb.Data data = output.build();
    assertThat(data.getLines(0).getUtLineHits()).isEqualTo(10);
    assertThat(data.getLines(0).getItLineHits()).isEqualTo(11);

    assertThat(data.getLines(1).hasUtLineHits()).isFalse();
    assertThat(data.getLines(1).getItLineHits()).isEqualTo(4);

    assertThat(data.getLines(2).getUtLineHits()).isEqualTo(4);
  }

  private void setupLineMeasure(Metric metric, String dataPerLine) {
    when(measureCache.byMetric(inputFile.key(), metric.key())).thenReturn(
      Arrays.asList(new Measure().setData(dataPerLine).setMetric(metric)));
  }

  @Test
  public void applyDuplications() throws Exception {
    DefaultDuplication group1 = new DefaultDuplication().setOriginBlock(new Duplication.Block(inputFile.key(), 1, 1));
    group1.duplicates().add(new Duplication.Block(inputFile.key(), 3, 1));
    group1.duplicates().add(new Duplication.Block("anotherFile1", 12, 1));
    group1.duplicates().add(new Duplication.Block("anotherFile2", 13, 1));
    DefaultDuplication group2 = new DefaultDuplication().setOriginBlock(new Duplication.Block(inputFile.key(), 1, 2));
    group2.duplicates().add(new Duplication.Block("anotherFile1", 12, 2));
    group2.duplicates().add(new Duplication.Block("anotherFile2", 13, 2));
    when(duplicationCache.byComponent(inputFile.key())).thenReturn(Lists.newArrayList(group1, group2));

    sut.applyDuplications(inputFile.key(), output);

    FileSourceDb.Data data = output.build();
    assertThat(data.getLines(0).getDuplicationList()).containsExactly(1, 3);
    assertThat(data.getLines(1).getDuplicationList()).containsExactly(3);
    assertThat(data.getLines(2).getDuplicationList()).containsExactly(2);
  }

  @Test
  public void applyDuplications_ignore_bad_lines() throws Exception {
    // duplication on 10 lines
    DefaultDuplication group1 = new DefaultDuplication().setOriginBlock(new Duplication.Block(inputFile.key(), 1, 10));
    group1.duplicates().add(new Duplication.Block("anotherFile1", 12, 1));
    group1.duplicates().add(new Duplication.Block("anotherFile2", 13, 1));
    when(duplicationCache.byComponent(inputFile.key())).thenReturn(Lists.newArrayList(group1));

    sut.applyDuplications(inputFile.key(), output);

    FileSourceDb.Data data = output.build();
    assertThat(data.getLines(0).getDuplicationList()).containsExactly(1);
    assertThat(data.getLines(1).getDuplicationList()).containsExactly(1);
    assertThat(data.getLines(2).getDuplicationList()).containsExactly(1);
  }

  @Test
  public void applyHighlighting_missing() throws Exception {
    when(componentDataCache.getData(inputFile.key(), SnapshotDataTypes.SYNTAX_HIGHLIGHTING)).thenReturn(null);

    sut.applyHighlighting(inputFile, metadata, output);

    FileSourceDb.Data data = output.build();
    assertThat(data.getLines(0).hasHighlighting()).isFalse();
    assertThat(data.getLines(1).hasHighlighting()).isFalse();
    assertThat(data.getLines(2).hasHighlighting()).isFalse();
  }

  @Test
  public void applyHighlighting() throws Exception {
    SyntaxHighlightingData highlighting = new SyntaxHighlightingDataBuilder()
      .registerHighlightingRule(0, 4, TypeOfText.ANNOTATION)
      .registerHighlightingRule(4, 5, TypeOfText.COMMENT)
      .registerHighlightingRule(7, 16, TypeOfText.CONSTANT)
      .build();
    when(componentDataCache.getData(inputFile.key(), SnapshotDataTypes.SYNTAX_HIGHLIGHTING)).thenReturn(highlighting);
    metadata.setOriginalLineOffsets(new int[] {0, 4, 7});

    sut.applyHighlighting(inputFile, metadata, output);

    FileSourceDb.Data data = output.build();
    assertThat(data.getLines(0).getHighlighting()).isEqualTo("0,4,a");
    assertThat(data.getLines(1).getHighlighting()).isEqualTo("0,1,cd");
    assertThat(data.getLines(2).getHighlighting()).isEqualTo("0,9,c");
  }

  @Test
  public void applyHighlighting_ignore_bad_line() throws Exception {
    SyntaxHighlightingData highlighting = new SyntaxHighlightingDataBuilder()
      .registerHighlightingRule(0, 4, TypeOfText.ANNOTATION)
      .registerHighlightingRule(4, 5, TypeOfText.COMMENT)
      .registerHighlightingRule(7, 25, TypeOfText.CONSTANT)
      .build();
    when(componentDataCache.getData(inputFile.key(), SnapshotDataTypes.SYNTAX_HIGHLIGHTING)).thenReturn(highlighting);
    metadata.setOriginalLineOffsets(new int[] {0, 4, 7, 15});

    sut.applyHighlighting(inputFile, metadata, output);

    FileSourceDb.Data data = output.build();
    assertThat(data.getLinesCount()).isEqualTo(3);
  }

  @Test
  public void applyHighlighting_multiple_lines() throws Exception {
    SyntaxHighlightingData highlighting = new SyntaxHighlightingDataBuilder()
      .registerHighlightingRule(0, 3, TypeOfText.ANNOTATION)
      .registerHighlightingRule(4, 9, TypeOfText.COMMENT)
      .registerHighlightingRule(10, 16, TypeOfText.CONSTANT)
      .build();
    when(componentDataCache.getData(inputFile.key(), SnapshotDataTypes.SYNTAX_HIGHLIGHTING)).thenReturn(highlighting);
    metadata.setOriginalLineOffsets(new int[] {0, 4, 7});

    sut.applyHighlighting(inputFile, metadata, output);

    FileSourceDb.Data data = output.build();
    assertThat(data.getLines(0).getHighlighting()).isEqualTo("0,3,a");
    assertThat(data.getLines(1).getHighlighting()).isEqualTo("0,3,cd");
    assertThat(data.getLines(2).getHighlighting()).isEqualTo("0,2,cd;3,9,c");
  }

  @Test
  public void applyHighlighting_nested_rules() throws Exception {
    SyntaxHighlightingData highlighting = new SyntaxHighlightingDataBuilder()
      .registerHighlightingRule(0, 3, TypeOfText.ANNOTATION)
      .registerHighlightingRule(4, 6, TypeOfText.COMMENT)
      .registerHighlightingRule(7, 16, TypeOfText.CONSTANT)
      .registerHighlightingRule(8, 15, TypeOfText.KEYWORD)
      .build();
    when(componentDataCache.getData(inputFile.key(), SnapshotDataTypes.SYNTAX_HIGHLIGHTING)).thenReturn(highlighting);
    metadata.setOriginalLineOffsets(new int[] {0, 4, 7});

    sut.applyHighlighting(inputFile, metadata, output);

    FileSourceDb.Data data = output.build();
    assertThat(data.getLines(0).getHighlighting()).isEqualTo("0,3,a");
    assertThat(data.getLines(1).getHighlighting()).isEqualTo("0,2,cd");
    assertThat(data.getLines(2).getHighlighting()).isEqualTo("0,9,c;1,8,k");
  }

  @Test
  public void applyHighlighting_nested_rules_and_multiple_lines() throws Exception {
    SyntaxHighlightingData highlighting = new SyntaxHighlightingDataBuilder()
      .registerHighlightingRule(0, 3, TypeOfText.ANNOTATION)
      .registerHighlightingRule(4, 6, TypeOfText.COMMENT)
      .registerHighlightingRule(4, 16, TypeOfText.CONSTANT)
      .registerHighlightingRule(8, 15, TypeOfText.KEYWORD)
      .build();
    when(componentDataCache.getData(inputFile.key(), SnapshotDataTypes.SYNTAX_HIGHLIGHTING)).thenReturn(highlighting);
    metadata.setOriginalLineOffsets(new int[] {0, 4, 7});

    sut.applyHighlighting(inputFile, metadata, output);

    FileSourceDb.Data data = output.build();
    assertThat(data.getLines(0).getHighlighting()).isEqualTo("0,3,a");
    assertThat(data.getLines(1).getHighlighting()).isEqualTo("0,3,c;0,2,cd");
    assertThat(data.getLines(2).getHighlighting()).isEqualTo("0,9,c;1,8,k");
  }

  @Test
  public void applySymbolReferences_missing() throws Exception {
    when(componentDataCache.getData(inputFile.key(), SnapshotDataTypes.SYMBOL_HIGHLIGHTING)).thenReturn(null);

    sut.applySymbolReferences(inputFile, metadata, output);

    FileSourceDb.Data data = output.build();
    assertThat(data.getLines(0).hasSymbols()).isFalse();
    assertThat(data.getLines(1).hasSymbols()).isFalse();
    assertThat(data.getLines(2).hasSymbols()).isFalse();
  }

  @Test
  public void applySymbolReferences() throws Exception {
    DefaultSymbolTableBuilder symbolBuilder = new DefaultSymbolTableBuilder(inputFile.key(), null);
    Symbol s1 = symbolBuilder.newSymbol(1, 2);
    symbolBuilder.newReference(s1, 4);
    symbolBuilder.newReference(s1, 11);
    Symbol s2 = symbolBuilder.newSymbol(4, 6);
    symbolBuilder.newReference(s2, 0);
    symbolBuilder.newReference(s2, 7);
    when(componentDataCache.getData(inputFile.key(), SnapshotDataTypes.SYMBOL_HIGHLIGHTING)).thenReturn(symbolBuilder.build());
    metadata.setOriginalLineOffsets(new int[] {0, 4, 7});

    sut.applySymbolReferences(inputFile, metadata, output);

    FileSourceDb.Data data = output.build();
    assertThat(data.getLines(0).getSymbols()).isEqualTo("1,2,1;0,2,2");
    assertThat(data.getLines(1).getSymbols()).isEqualTo("0,1,1;0,2,2");
    assertThat(data.getLines(2).getSymbols()).isEqualTo("4,5,1;0,2,2");
  }

  @Test
  public void applySymbolReferences_declaration_order_is_not_important() throws Exception {
    DefaultSymbolTableBuilder symbolBuilder = new DefaultSymbolTableBuilder(inputFile.key(), null);
    Symbol s2 = symbolBuilder.newSymbol(4, 6);
    symbolBuilder.newReference(s2, 7);
    symbolBuilder.newReference(s2, 0);
    Symbol s1 = symbolBuilder.newSymbol(1, 2);
    symbolBuilder.newReference(s1, 11);
    symbolBuilder.newReference(s1, 4);
    when(componentDataCache.getData(inputFile.key(), SnapshotDataTypes.SYMBOL_HIGHLIGHTING)).thenReturn(symbolBuilder.build());
    metadata.setOriginalLineOffsets(new int[] {0, 4, 7});

    sut.applySymbolReferences(inputFile, metadata, output);

    FileSourceDb.Data data = output.build();
    assertThat(data.getLines(0).getSymbols()).isEqualTo("1,2,1;0,2,2");
    assertThat(data.getLines(1).getSymbols()).isEqualTo("0,1,1;0,2,2");
    assertThat(data.getLines(2).getSymbols()).isEqualTo("4,5,1;0,2,2");
  }
}
