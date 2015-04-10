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
package org.sonar.server.qualityprofile.ws;

import com.google.common.collect.MapDifference.ValueDifference;
import com.google.common.collect.Maps;
import org.sonar.api.rule.RuleKey;
import org.sonar.api.server.ws.Request;
import org.sonar.api.server.ws.Response;
import org.sonar.api.server.ws.WebService.NewAction;
import org.sonar.api.server.ws.WebService.NewController;
import org.sonar.api.utils.text.JsonWriter;
import org.sonar.core.qualityprofile.db.QualityProfileDto;
import org.sonar.core.util.NonNullInputFunction;
import org.sonar.server.qualityprofile.ActiveRule;
import org.sonar.server.qualityprofile.QProfileComparator;
import org.sonar.server.qualityprofile.QProfileComparator.ActiveRuleDiff;
import org.sonar.server.qualityprofile.QProfileComparator.QProfileComparisonResult;
import org.sonar.server.rule.Rule;
import org.sonar.server.rule.RuleRepositories;
import org.sonar.server.rule.RuleRepositories.Repository;
import org.sonar.server.rule.RuleService;

import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

public class QProfileCompareAction implements BaseQProfileWsAction {

  private static final String PARAM_LEFT_KEY = "leftKey";
  private static final String PARAM_RIGHT_KEY = "rightKey";

  private final QProfileComparator comparator;
  private final RuleService ruleService;
  private final RuleRepositories ruleRepositories;

  public QProfileCompareAction(QProfileComparator comparator, RuleService ruleService, RuleRepositories ruleRepositories) {
    this.comparator = comparator;
    this.ruleService = ruleService;
    this.ruleRepositories = ruleRepositories;
  }

  @Override
  public void define(NewController context) {
    NewAction compare = context.createAction("compare")
      .setDescription("Compare two quality profiles.")
      .setHandler(this)
      .setResponseExample(getClass().getResource("example-compare.json"))
      .setSince("5.2");

    compare.createParam(PARAM_LEFT_KEY)
      .setDescription("A profile key.")
      .setExampleValue("java-sonar-way-12345")
      .setRequired(true);

    compare.createParam(PARAM_RIGHT_KEY)
      .setDescription("Another profile key.")
      .setExampleValue("java-sonar-way-with-findbugs-23456")
      .setRequired(true);
  }

  @Override
  public void handle(Request request, Response response) throws Exception {
    String leftKey = request.mandatoryParam(PARAM_LEFT_KEY);
    String rightKey = request.mandatoryParam(PARAM_RIGHT_KEY);

    QProfileComparisonResult result = comparator.compare(leftKey, rightKey);

    List<Rule> referencedRules = ruleService.getByKeys(result.collectRuleKeys());
    Map<RuleKey, Rule> rulesByKey = Maps.uniqueIndex(referencedRules, new NonNullInputFunction<Rule, RuleKey>() {
      @Override
      protected RuleKey doApply(Rule input) {
        return input.key();
      }
    });

    writeResult(response.newJsonWriter(), result, rulesByKey);
  }

  private void writeResult(JsonWriter json, QProfileComparisonResult result, Map<RuleKey, Rule> rulesByKey) {
    json.beginObject();

    json.name("left").beginObject();
    writeProfile(json, result.left());
    json.endObject();

    json.name("right").beginObject();
    writeProfile(json, result.right());
    json.endObject();

    json.name("inLeft");
    writeRules(json, result.inLeft(), rulesByKey);

    json.name("inRight");
    writeRules(json, result.inRight(), rulesByKey);

    json.name("modified");
    writeDifferences(json, result.modified(), rulesByKey);

    json.name("same");
    writeRules(json, result.same(), rulesByKey);

    json.endObject().close();
  }

  private void writeProfile(JsonWriter json, QualityProfileDto profile) {
    json.prop("key", profile.getKey())
      .prop("name", profile.getName());
  }

  private void writeRules(JsonWriter json, Map<RuleKey, ActiveRule> activeRules, Map<RuleKey, Rule> rulesByKey) {
    json.beginArray();
    for (Entry<RuleKey, ActiveRule> activeRule : activeRules.entrySet()) {
      RuleKey key = activeRule.getKey();
      ActiveRule value = activeRule.getValue();
      json.beginObject()
        .prop("key", key.toString())
        .prop("name", rulesByKey.get(key).name())
        .prop("pluginKey", key.repository())
        .prop("severity", value.severity());

      Repository repo = ruleRepositories.repository(key.repository());
      if (repo != null) {
        json.prop("pluginName", repo.getName());
      }

      json.endObject();
    }
    json.endArray();
  }

  private void writeDifferences(JsonWriter json, Map<RuleKey, ActiveRuleDiff> modified, Map<RuleKey, Rule> rulesByKey) {
    json.beginArray();
    for (Entry<RuleKey, ActiveRuleDiff> diffEntry : modified.entrySet()) {
      RuleKey key = diffEntry.getKey();
      ActiveRuleDiff value = diffEntry.getValue();
      json.beginObject()
        .prop("key", key.toString())
        .prop("name", rulesByKey.get(key).name());

      json.name("left").beginObject();
      json.prop("severity", value.leftSeverity());
      json.name("params").beginObject();
      for (Entry<String, ValueDifference<String>> valueDiff : value.paramDifference().entriesDiffering().entrySet()) {
        json.prop(valueDiff.getKey(), valueDiff.getValue().leftValue());
      }
      for (Entry<String, String> valueDiff : value.paramDifference().entriesOnlyOnLeft().entrySet()) {
        json.prop(valueDiff.getKey(), valueDiff.getValue());
      }
      json.endObject();
      json.endObject();

      json.name("right").beginObject();
      json.prop("severity", value.rightSeverity());
      json.name("params").beginObject();
      for (Entry<String, ValueDifference<String>> valueDiff : value.paramDifference().entriesDiffering().entrySet()) {
        json.prop(valueDiff.getKey(), valueDiff.getValue().rightValue());
      }
      for (Entry<String, String> valueDiff : value.paramDifference().entriesOnlyOnRight().entrySet()) {
        json.prop(valueDiff.getKey(), valueDiff.getValue());
      }
      json.endObject();
      json.endObject();

      json.endObject();
    }
    json.endArray();
  }

}
