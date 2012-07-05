/////////////////////////////////////////////////////////////////////////////
//
// Project   ProjectForge
//
// Copyright 2001-2009, Micromata GmbH, Kai Reinhard
//           All rights reserved.
//
/////////////////////////////////////////////////////////////////////////////

package org.projectforge.plugins.skillmatrix;

import org.apache.log4j.Logger;
import org.projectforge.core.BaseSearchFilter;
import org.projectforge.web.wicket.AbstractListForm;

/**
 * @author Billy Duong (duong.billy@yahoo.de)
 * 
 */
public class SkillRatingListForm extends AbstractListForm<BaseSearchFilter, SkillRatingListPage>
{
  private static final long serialVersionUID = 5333752125044497290L;

  private static final org.apache.log4j.Logger log = org.apache.log4j.Logger.getLogger(SkillRatingListForm.class);

  /**
   * @param parentPage
   */
  public SkillRatingListForm(final SkillRatingListPage parentPage)
  {
    super(parentPage);
  }

  /**
   * @see org.projectforge.web.wicket.AbstractListForm#newSearchFilterInstance()
   */
  @Override
  protected BaseSearchFilter newSearchFilterInstance()
  {
    return new BaseSearchFilter();
  }

  /**
   * @see org.projectforge.web.wicket.AbstractListForm#getLogger()
   */
  @Override
  protected Logger getLogger()
  {
    return log;
  }

}
