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

package org.sonar.server.source.ws;

import org.sonar.api.server.ws.WebService;

public class SourcesWs implements WebService {

  private final ShowAction showAction;
  private final LinesAction linesAction;
  private final RawAction rawAction;
  private final HashAction hashAction;
  private final IndexAction indexAction;

  public SourcesWs(ShowAction showAction, RawAction rawAction, LinesAction linesAction, HashAction hashAction, IndexAction indexAction) {
    this.showAction = showAction;
    this.linesAction = linesAction;
    this.rawAction = rawAction;
    this.hashAction = hashAction;
    this.indexAction = indexAction;
  }

  @Override
  public void define(Context context) {
    NewController controller = context.createController("api/sources")
      .setSince("4.2")
      .setDescription("Display sources information");
    showAction.define(controller);
    linesAction.define(controller);
    rawAction.define(controller);
    hashAction.define(controller);
    indexAction.define(controller);
    controller.done();
  }
}
