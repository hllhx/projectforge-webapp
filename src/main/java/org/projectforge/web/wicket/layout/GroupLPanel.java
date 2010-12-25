/////////////////////////////////////////////////////////////////////////////
//
// Project ProjectForge Community Edition
//         www.projectforge.org
//
// Copyright (C) 2001-2010 Kai Reinhard (k.reinhard@me.com)
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

package org.projectforge.web.wicket.layout;

import static org.projectforge.web.wicket.layout.LayoutLength.HALF;

import org.apache.wicket.markup.html.basic.Label;
import org.apache.wicket.markup.html.panel.Panel;
import org.apache.wicket.markup.repeater.RepeatingView;

/**
 * Represents a group panel. A field set, form or page can contain multiple group panels. A group panel groups fields.
 * @author Kai Reinhard (k.reinhard@micromata.de)
 * 
 */
public class GroupLPanel extends Panel
{
  private static final long serialVersionUID = -8760386387270114082L;

  /**
   * The markup wicket id of the heading label.
   */
  public static final String HEADING_ID = "heading";

  private Label headingLabel;

  private RepeatingView entriesRepeater;

  GroupLPanel(final String id)
  {
    super(id);
  }

  GroupLPanel(final String id, final String heading)
  {
    this(id);
    if (heading != null) {
      setHeading(heading);
    }
  }

  public TextFieldLPanel addMaxLengthTextField(final Object dataObject, final String property, final String labelKey,
      final LayoutLength length)
  {
    final TextFieldLPanel textFieldPanel = new TextFieldLPanel(newChildId(), length, dataObject, property);
    add(new LabelLPanel(newChildId(), HALF, getString(labelKey)).setLabelFor(textFieldPanel.getTextField()).setBreakBefore());
    add(textFieldPanel);
    return textFieldPanel;
  }

  public TextAreaLPanel addMaxLengthTextArea(final Object dataObject, final String property, final String labelKey,
      final LayoutLength length)
  {
    final TextAreaLPanel textAreaPanel = new TextAreaLPanel(newChildId(), length, dataObject, property);
    add(new LabelLPanel(newChildId(), HALF, getString(labelKey)).setLabelFor(textAreaPanel.getTextArea()).setBreakBefore());
    add(textAreaPanel);
    return textAreaPanel;
  }

  public GroupLPanel add(final AbstractLPanel layoutPanel)
  {
    entriesRepeater.add(layoutPanel);
    return this;
  }

  public String newChildId()
  {
    if (entriesRepeater == null) {
      init();
    }
    return entriesRepeater.newChildId();
  }

  /**
   * Should only be called manually if no children are added to this field set. Otherwise it'll be initialized at the first call of
   * newChildId().
   */
  public GroupLPanel init()
  {
    if (entriesRepeater != null) {
      return this;
    }
    if (this.headingLabel != null) {
      add(this.headingLabel);
    } else {
      add(new Label(HEADING_ID, "[invisible]").setVisible(false));
    }
    entriesRepeater = new RepeatingView("entriesRepeater");
    add(entriesRepeater);
    return this;
  }

  public void setHeading(final Label headingLabel)
  {
    this.headingLabel = headingLabel;
  }

  /**
   * @param heading
   * @return this for chaining.
   */
  public GroupLPanel setHeading(final String heading)
  {
    if (heading != null) {
      this.headingLabel = new Label(HEADING_ID, heading);
    }
    return this;
  }
}
