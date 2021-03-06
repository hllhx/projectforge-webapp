/////////////////////////////////////////////////////////////////////////////
//
// Project ProjectForge Community Edition
//         www.projectforge.org
//
// Copyright (C) 2001-2014 Kai Reinhard (k.reinhard@micromata.de)
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

package org.projectforge.plugins.teamcal.externalsubscription;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.apache.commons.collections.CollectionUtils;
import org.hibernate.criterion.Restrictions;
import org.projectforge.common.DateHelper;
import org.projectforge.core.QueryFilter;
import org.projectforge.plugins.teamcal.admin.TeamCalAccessType;
import org.projectforge.plugins.teamcal.admin.TeamCalCache;
import org.projectforge.plugins.teamcal.admin.TeamCalDO;
import org.projectforge.plugins.teamcal.admin.TeamCalDao;
import org.projectforge.plugins.teamcal.admin.TeamCalRight;
import org.projectforge.plugins.teamcal.event.TeamEventDO;
import org.projectforge.plugins.teamcal.event.TeamEventFilter;
import org.projectforge.user.PFUserContext;
import org.projectforge.user.UserRights;

/**
 * @author Johannes Unterstein (j.unterstein@micromata.de)
 */
public class TeamEventExternalSubscriptionCache
{
  private static final TeamEventExternalSubscriptionCache instance = new TeamEventExternalSubscriptionCache();

  private static final org.apache.log4j.Logger log = org.apache.log4j.Logger.getLogger(TeamEventExternalSubscriptionCache.class);

  private static final long MAX_WAIT_MS_AFTER_FAILED_UPDATE = 1000 * 60 * 60 * 24; // 24 h

  private final Map<Integer, TeamEventSubscription> subscriptions;

  private static final Long SUBSCRIPTION_UPDATE_TIME = 5L * 60 * 1000; // 5 min

  private transient TeamCalRight teamCalRight;

  private TeamEventExternalSubscriptionCache()
  {
    subscriptions = new HashMap<Integer, TeamEventSubscription>();
  }

  public static TeamEventExternalSubscriptionCache instance()
  {
    return instance;
  }

  public void updateCache(final TeamCalDao dao)
  {
    final QueryFilter filter = new QueryFilter();
    filter.add(Restrictions.eq("externalSubscription", true));
    // internalGetList is valid at this point, because we are calling this method in an asyn thread
    final List<TeamCalDO> subscribedCalendars = dao.internalGetList(filter);

    for (final TeamCalDO calendar : subscribedCalendars) {
      updateCache(dao, calendar);
    }

    final List<Integer> idsToRemove = new ArrayList<Integer>();
    for (final Integer calendarId : subscriptions.keySet()) {
      // if calendar is not subscribed anymore, remove them
      if (calendarListContainsId(subscribedCalendars, calendarId) == false) {
        idsToRemove.add(calendarId);
      }
    }
    removeCalendarsFromCache(idsToRemove);
  }

  private void removeCalendarsFromCache(final List<Integer> idsToRemove)
  {
    for (final Integer calendarId : idsToRemove) {
      subscriptions.remove(calendarId);
    }
  }

  private boolean calendarListContainsId(final List<TeamCalDO> subscribedCalendars, final Integer calendarId)
  {
    for (final TeamCalDO teamCal : subscribedCalendars) {
      if (teamCal.getId().equals(calendarId)) {
        return true;
      }
    }
    return false;
  }

  public void updateCache(final TeamCalDao dao, final TeamCalDO calendar)
  {
    updateCache(dao, calendar, false);
  }

  /**
   * @param dao
   * @param calendar
   * @param force If true then update is forced (independent of last update time and refresh interval).
   */
  public void updateCache(final TeamCalDao dao, final TeamCalDO calendar, final boolean force)
  {
    final Integer calId = calendar.getId();
    if (calId == null) {
      log.error("Oups, calId is null (can't update subscription): " + calendar);
      return;
    }
    TeamEventSubscription teamEventSubscription = subscriptions.get(calId);
    final Long now = System.currentTimeMillis();
    final Long addedTime = calendar.getExternalSubscriptionUpdateInterval() == null ? SUBSCRIPTION_UPDATE_TIME : 1000L * calendar
        .getExternalSubscriptionUpdateInterval();
    if (teamEventSubscription == null) {
      // First update of subscribed calendar:
      teamEventSubscription = new TeamEventSubscription();
      subscriptions.put(calendar.getId(), teamEventSubscription);
      teamEventSubscription.update(dao, calendar);
    } else if (force == true || teamEventSubscription.getLastUpdated() == null || teamEventSubscription.getLastUpdated() + addedTime <= now) {
      if (force == false && teamEventSubscription.getNumberOfFailedUpdates() > 0) {
        // Errors occurred and update not forced. Don't update e. g. every 5 minutes if a permanently error occurs.
        Long lastRun = teamEventSubscription.getLastUpdated();
        if (lastRun == null) {
          lastRun = teamEventSubscription.getLastFailedUpdate();
        }
        if (lastRun == null || lastRun + teamEventSubscription.getNumberOfFailedUpdates() * addedTime <= now) {
          teamEventSubscription.update(dao, calendar);
        } else if (lastRun + MAX_WAIT_MS_AFTER_FAILED_UPDATE > now) {
          log.info("Try to update subscribed calendar after "
              + (MAX_WAIT_MS_AFTER_FAILED_UPDATE / 1000 / 60 / 60)
              + " hours. Number of failed updates: "
              + teamEventSubscription.getNumberOfFailedUpdates()
              + ", time of last successful update (UTC): "
              + (teamEventSubscription.getLastUpdated() != null ? DateHelper.formatAsUTC(new Date(teamEventSubscription.getLastUpdated()))
                  : "-"));
          teamEventSubscription.update(dao, calendar);
        }
      } else {
        // update the calendar
        teamEventSubscription.update(dao, calendar);
      }
    }
  }

  public boolean isExternalSubscribedCalendar(final Integer calendarId)
  {
    return subscriptions.keySet().contains(calendarId) == true;
  }

  public List<TeamEventDO> getEvents(final Integer calendarId, final Long startTime, final Long endTime)
  {
    final TeamEventSubscription eventSubscription = subscriptions.get(calendarId);
    if (eventSubscription == null) {
      return null;
    }
    final Integer userId = PFUserContext.getUserId();
    final TeamCalAccessType accessType = getAccessType(eventSubscription.getTeamCalId(), userId);
    if (accessType == TeamCalAccessType.NONE) {
      return null;
    }
    return eventSubscription.getEvents(startTime, endTime, accessType == TeamCalAccessType.MINIMAL);
  }

  public List<TeamEventDO> getRecurrenceEvents(final TeamEventFilter filter)
  {
    final List<TeamEventDO> result = new ArrayList<TeamEventDO>();
    // precondition: existing teamcals ins filter
    final Collection<Integer> teamCals = new LinkedList<Integer>();
    final Integer userId = PFUserContext.getUserId();
    if (CollectionUtils.isNotEmpty(filter.getTeamCals()) == true) {
      for (final Integer calendarId : filter.getTeamCals()) {
        final TeamEventSubscription eventSubscription = subscriptions.get(calendarId);
        if (eventSubscription == null) {
          continue;
        }
        final TeamCalDO calendar = TeamCalCache.getInstance().getCalendar(calendarId);
        if (getTeamCalRight().getAccessType(calendar, userId).isIn(TeamCalAccessType.FULL, TeamCalAccessType.READONLY,
            TeamCalAccessType.MINIMAL) == false) {
          continue;
        }
        teamCals.add(calendarId);
      }
    }
    if (filter.getTeamCalId() != null) {
      final TeamEventSubscription eventSubscription = subscriptions.get(filter.getTeamCalId());
      if (eventSubscription != null) {
        final TeamCalDO cal = TeamCalCache.getInstance().getCalendar(filter.getTeamCalId());
        if (getTeamCalRight().getAccessType(cal, userId)
            .isIn(TeamCalAccessType.FULL, TeamCalAccessType.READONLY, TeamCalAccessType.MINIMAL) == true) {
          teamCals.add(filter.getTeamCalId());
        }
      }
    }
    if (teamCals != null) {
      for (final Integer calendarId : teamCals) {
        final TeamEventSubscription eventSubscription = subscriptions.get(calendarId);
        if (eventSubscription != null) {
          final List<TeamEventDO> recurrenceEvents = eventSubscription.getRecurrenceEvents();
          if (recurrenceEvents != null && recurrenceEvents.size() > 0) {
            for (final TeamEventDO event : recurrenceEvents) {
              final TeamCalDO calendar = TeamCalCache.getInstance().getCalendar(calendarId);
              if (getTeamCalRight().getAccessType(calendar, userId) == TeamCalAccessType.MINIMAL) {
                result.add(event.createMinimalCopy());
              } else {
                result.add(event);
              }
            }
          }
        }
      }
    }
    return result;
  }

  private TeamCalAccessType getAccessType(final Integer calendarId, final Integer userId)
  {
    final TeamCalDO cal = TeamCalCache.getInstance().getCalendar(calendarId);
    return getTeamCalRight().getAccessType(cal, userId);
  }

  /**
   * @return the teamCalRight
   */
  public TeamCalRight getTeamCalRight()
  {
    if (teamCalRight == null) {
      teamCalRight = (TeamCalRight) UserRights.instance().getRight(TeamCalDao.USER_RIGHT_ID);
    }
    return teamCalRight;
  }
}
