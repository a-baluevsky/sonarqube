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
package org.sonar.server.activity.index;

import org.apache.commons.dbutils.DbUtils;
import org.assertj.core.data.MapEntry;
import org.elasticsearch.action.update.UpdateRequest;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.sonar.api.utils.DateUtils;
import org.sonar.core.persistence.DbTester;
import org.sonar.server.db.DbClient;
import org.sonar.test.DbTests;

import java.sql.Connection;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Category(DbTests.class)
public class ActivityResultSetIteratorTest {

  @ClassRule
  public static DbTester dbTester = new DbTester();

  DbClient client;
  Connection connection;

  @Before
  public void setUp() throws Exception {
    dbTester.truncateTables();
    client = new DbClient(dbTester.database(), dbTester.myBatis());
    connection = dbTester.openConnection();
  }

  @After
  public void tearDown() throws Exception {
    DbUtils.closeQuietly(connection);
  }

  /**
   * Iterate over two rows in table.
   */
  @Test
  public void traverse() throws Exception {
    dbTester.prepareDbUnit(getClass(), "traverse.xml");
    ActivityResultSetIterator it = ActivityResultSetIterator.create(client, connection, 0L);

    assertThat(it.hasNext()).isTrue();
    UpdateRequest request = it.next();
    Map<String, Object> doc = request.doc().sourceAsMap();
    assertThat(doc.get(ActivityIndexDefinition.FIELD_KEY)).isEqualTo("UUID1");
    assertThat(doc.get(ActivityIndexDefinition.FIELD_ACTION)).isEqualTo("THE_ACTION");
    assertThat(doc.get(ActivityIndexDefinition.FIELD_MESSAGE)).isEqualTo("THE_MSG");
    assertThat((Map) doc.get(ActivityIndexDefinition.FIELD_DETAILS)).containsOnly(MapEntry.entry("foo", "bar"));
    assertThat(doc.get(ActivityIndexDefinition.FIELD_LOGIN)).isEqualTo("THE_AUTHOR");

    assertThat(it.hasNext()).isTrue();
    assertThat(it.next()).isNotNull();
    assertThat(it.hasNext()).isFalse();
    it.close();

    assertThat(formatLongDate(it.getMaxRowDate())).startsWith("2015-01-01");
  }

  @Test
  public void traverse_after_date() throws Exception {
    dbTester.prepareDbUnit(getClass(), "traverse.xml");
    ActivityResultSetIterator it = ActivityResultSetIterator.create(client, connection, DateUtils.parseDate("2014-12-01").getTime());

    assertThat(it.hasNext()).isTrue();
    UpdateRequest request = it.next();
    assertThat(request).isNotNull();
    Map<String, Object> doc = request.doc().sourceAsMap();
    assertThat(doc.get(ActivityIndexDefinition.FIELD_KEY)).isEqualTo("UUID2");

    assertThat(it.hasNext()).isFalse();
    it.close();

    assertThat(formatLongDate(it.getMaxRowDate())).startsWith("2015-01-01");
  }

  @Test
  public void nothing_to_traverse() throws Exception {
    dbTester.prepareDbUnit(getClass(), "traverse.xml");
    ActivityResultSetIterator it = ActivityResultSetIterator.create(client, connection, DateUtils.parseDate("2030-01-01").getTime());

    assertThat(it.hasNext()).isFalse();
    it.close();

    assertThat(it.getMaxRowDate()).isEqualTo(0L);
  }

  private String formatLongDate(long dateInMs) {
    return DateUtils.formatDateTime(DateUtils.longToDate(dateInMs));
  }
}
