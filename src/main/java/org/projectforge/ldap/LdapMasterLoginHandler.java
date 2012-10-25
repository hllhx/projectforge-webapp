/////////////////////////////////////////////////////////////////////////////
//
// Project ProjectForge Community Edition
//         www.projectforge.org
//
// Copyright (C) 2001-2012 Kai Reinhard (k.reinhard@micromata.com)
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

package org.projectforge.ldap;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.naming.NameNotFoundException;

import org.apache.commons.lang.StringUtils;
import org.projectforge.registry.Registry;
import org.projectforge.user.GroupDO;
import org.projectforge.user.LoginDefaultHandler;
import org.projectforge.user.LoginResult;
import org.projectforge.user.LoginResultStatus;
import org.projectforge.user.PFUserDO;

/**
 * TODO: nested groups.<br/>
 * This LDAP login handler has read-write access to the LDAP server and acts as master of the user and group data. All changes of
 * ProjectForge's users and groups will be written through. Any change of the LDAP server will be ignored and may be overwritten by
 * ProjectForge. <br/>
 * Use this login handler if you want to configure your LDAP users and LDAP groups via ProjectForge.<br/>
 * <h1>Passwords</h1> After each successful login-in at ProjectForge (via LoginForm) ProjectForges tries to authenticate the user with the
 * given username/password credentials at LDAP. If the LDAP authentication fails ProjectForge changes the password with the actual password
 * of the user (given in the LoginForm). <h1>Deactivated users</h1> Deactivated users will be moved to an sub userbase called "deactivated".
 * The e-mail will be invalidated and the password will be deleted. Deleted and deactivated users are removed from any LDAP group. After
 * reactivating the user, the password has to be reset if the user logins the next time via LoginForm. <h1>Deleted Users</h1> Deleted users
 * will not be synchronized and removed in LDAP if exist. <h1>Stay-logged-in</h1> The stay-logged-in mechanism will be ignored if the LDAP
 * password of the user isn't set (is null). Any existing LDAP password doesn't interrupt the normal stay-logged-in mechanism. <h1>New users
 * </h1> New users (created with ProjectForge's UserEditPage) will be created first without password in the LDAP system directly. Such users
 * need to log-in first at ProjectForge, otherwise their LDAP passwords aren't set (no log-in at any other system connecting to the LDAP is
 * possible until the first log-in at ProjectForge).
 * @author Kai Reinhard (k.reinhard@micromata.de)
 * 
 */
public class LdapMasterLoginHandler extends LdapLoginHandler
{
  private static final org.apache.log4j.Logger log = org.apache.log4j.Logger.getLogger(LdapMasterLoginHandler.class);

  /**
   * For users of this list, the stay-logged-in mechanism interrupts, the user has to re-login via LoginForm to update the correct password
   * in the LDAP system.
   */
  private Set<Integer> usersWithoutLdapPasswords = new HashSet<Integer>();

  private boolean refreshInProgress;

  /**
   * @see org.projectforge.ldap.LdapLoginHandler#initialize()
   */
  @Override
  public void initialize()
  {
    super.initialize();
    ldapOrganizationalUnitDao.createIfNotExist(userBase, "ProjectForge's user base.");
    ldapOrganizationalUnitDao.createIfNotExist(LdapUserDao.DEACTIVATED_SUB_CONTEXT, "ProjectForge's user base for deactivated users.",
        userBase);
    ldapOrganizationalUnitDao.createIfNotExist(LdapUserDao.RESTRICTED_USER_SUB_CONTEXT, "ProjectForge's user base for restricted users.",
        userBase);
    ldapOrganizationalUnitDao.createIfNotExist(groupBase, "ProjectForge's group base.");
  }

  /**
   * @see org.projectforge.user.LoginHandler#checkLogin(java.lang.String, java.lang.String, boolean)
   */
  @Override
  public LoginResult checkLogin(final String username, final String password)
  {
    final LoginResult loginResult = loginDefaultHandler.checkLogin(username, password);
    if (loginResult.getLoginResultStatus() != LoginResultStatus.SUCCESS) {
      return loginResult;
    }
    // User is now logged-in successfully.
    LdapPerson ldapUser = ldapUserDao.authenticate(username, password, userBase);
    if (ldapUser == null) {
      log.info("User's credentials in LDAP not up-to-date: " + username + ". Updating LDAP entry...");
      final PFUserDO user = loginResult.getUser();
      ldapUser = PFUserDOConverter.convert(user);
      ldapUser.setOrganizationalUnit(userBase);
      ldapUserDao.createOrUpdate(userBase, ldapUser);
      ldapUserDao.changePassword(ldapUser, null, password);
    }
    return loginResult;
  }

  /**
   * @see org.projectforge.user.LoginHandler#getAllGroups()
   */
  @Override
  public List<GroupDO> getAllGroups()
  {
    final List<GroupDO> groups = loginDefaultHandler.getAllGroups();
    return groups;
  }

  /**
   * @see org.projectforge.user.LoginHandler#getAllUsers()
   */
  @Override
  public List<PFUserDO> getAllUsers()
  {
    final List<PFUserDO> users = loginDefaultHandler.getAllUsers();
    return users;
  }

  /**
   * Refreshes the LDAP.
   * @see org.projectforge.user.LoginHandler#afterUserGroupCacheRefresh(java.util.List, java.util.List)
   */
  @Override
  public void afterUserGroupCacheRefresh(final Collection<PFUserDO> users, final Collection<GroupDO> groups)
  {
    new Thread() {
      @Override
      public void run()
      {
        synchronized (this) {
          try {
            refreshInProgress = true;
            updateLdap(users, groups);
          } finally {
            refreshInProgress = false;
          }
        }
      }
    }.start();
  }

  /**
   * @return true if currently a cache refresh is running, otherwise false.
   */
  public boolean isRefreshInProgress()
  {
    return refreshInProgress;
  }

  private void updateLdap(final Collection<PFUserDO> users, final Collection<GroupDO> groups)
  {
    new LdapTemplate(ldapConnector) {
      @Override
      protected Object call() throws NameNotFoundException, Exception
      {
        log.info("Updating LDAP...");
        // First, get set of all ldap entries:
        final List<LdapPerson> ldapUsers = getAllLdapUsers(ctx);
        final List<LdapPerson> updatedLdapUsers = new ArrayList<LdapPerson>();
        int error = 0, unmodified = 0, created = 0, updated = 0, deleted = 0, renamed = 0;
        final Set<Integer> shadowUsersWithoutLdapPasswords = new HashSet<Integer>();
        for (final PFUserDO user : users) {
          try {
            final LdapPerson updatedLdapUser = PFUserDOConverter.convert(user);
            final LdapPerson ldapUser = getLdapUser(ldapUsers, user);
            if (ldapUser == null) {
              updatedLdapUser.setOrganizationalUnit(userBase);
              if (user.isDeleted() == false && user.isLocalUser() == false) {
                // Do not add deleted or local users.
                ldapUserDao.create(ctx, userBase, updatedLdapUser);
                updatedLdapUsers.add(updatedLdapUser);
                shadowUsersWithoutLdapPasswords.add(user.getId()); // User can't be valid for created users.
                created++;
              }
            } else {
              // Need to set organizational unit for detecting the change of deactivated flag. The updateLdapUser needs the organizational
              // unit of the original ldap object:
              updatedLdapUser.setOrganizationalUnit(ldapUser.getOrganizationalUnit());
              if (user.isDeleted() == true || user.isLocalUser() == true) {
                // Deleted and local users shouldn't be synchronized with LDAP:
                ldapUserDao.delete(ctx, updatedLdapUser);
                shadowUsersWithoutLdapPasswords.add(user.getId()); // Paranoia code, stay-logged-in shouldn't work with deleted users.
                deleted++;
              } else {
                final boolean modified = PFUserDOConverter.copyUserFields(updatedLdapUser, ldapUser);
                if (modified == true) {
                  ldapUserDao.update(ctx, userBase, updatedLdapUser);
                  updated++;
                } else {
                  unmodified++;
                }
                if (StringUtils.equals(updatedLdapUser.getUid(), ldapUser.getUid()) == false) {
                  // uid (dn) changed.
                  ldapUserDao.rename(ctx, updatedLdapUser, ldapUser);
                  renamed++;
                }
                if (ldapUser.isPasswordGiven() == true) {
                  if (updatedLdapUser.isDeactivated()) {
                    log.warn("User password for deactivated user is set: " + ldapUser);
                    ldapUserDao.deactivateUser(ctx, updatedLdapUser);
                    shadowUsersWithoutLdapPasswords.add(user.getId()); // Paranoia code, stay-logged-in shouldn't work with deleted or
                    // deactivated users.
                  } else {
                    shadowUsersWithoutLdapPasswords.remove(user.getId()); // Remove if exists because password is given.
                  }
                } else {
                  shadowUsersWithoutLdapPasswords.add(user.getId()); // Password isn't given for the current user.
                }
                updatedLdapUsers.add(updatedLdapUser);
              }
            }
            ldapUserDao.buildDn(userBase, updatedLdapUser);
          } catch (final Exception ex) {
            log.error("Error while proceeding user '" + user.getUsername() + "'. Continuing with next user.", ex);
            error++;
          }
        }
        usersWithoutLdapPasswords = shadowUsersWithoutLdapPasswords;
        log.info(""
            + shadowUsersWithoutLdapPasswords.size()
            + " users without password in the LDAP system (login required for these users for updating the LDAP password).");
        log.info("Update of LDAP users: "
            + (error > 0 ? "*** " + error + " errors ***, " : "")
            + unmodified
            + " unmodified, "
            + created
            + " created, "
            + updated
            + " updated, "
            + renamed
            + " renamed, "
            + deleted
            + " deleted.");
        // Now get all groups:
        final List<LdapGroup> ldapGroups = getAllLdapGroups(ctx);
        final Map<Integer, LdapPerson> ldapUserMap = getUserMap(updatedLdapUsers);
        error = unmodified = created = updated = renamed = deleted = 0;
        for (final GroupDO group : groups) {
          try {
            final LdapGroup updatedLdapGroup = GroupDOConverter.convert(group, baseDN, ldapUserMap);
            final LdapGroup ldapGroup = getLdapGroup(ldapGroups, group);
            if (ldapGroup == null) {
              updatedLdapGroup.setOrganizationalUnit(groupBase);
              if (group.isDeleted() == false && group.isLocalGroup() == false) {
                // Do not add deleted or local groups.
                setMembers(updatedLdapGroup, group.getAssignedUsers(), ldapUserMap);
                ldapGroupDao.create(ctx, groupBase, updatedLdapGroup);
                created++;
              }
            } else {
              updatedLdapGroup.setOrganizationalUnit(ldapGroup.getOrganizationalUnit());
              if (group.isDeleted() == true || group.isLocalGroup() == true) {
                // Deleted and local users shouldn't be synchronized with LDAP:
                ldapGroupDao.delete(ctx, updatedLdapGroup);
                deleted++;
              } else {
                final boolean modified = GroupDOConverter.copyGroupFields(updatedLdapGroup, ldapGroup);
                if (modified == true) {
                  setMembers(updatedLdapGroup, group.getAssignedUsers(), ldapUserMap);
                  ldapGroupDao.update(ctx, groupBase, updatedLdapGroup);
                  updated++;
                } else {
                  unmodified++;
                }
                if (StringUtils.equals(updatedLdapGroup.getCommonName(), ldapGroup.getCommonName()) == false) {
                  // CommonName (cn) and therefor dn changed.
                  ldapGroupDao.rename(ctx, updatedLdapGroup, ldapGroup);
                  renamed++;
                }
              }
            }
          } catch (final Exception ex) {
            log.error("Error while proceeding group '" + group.getName() + "'. Continuing with next group.", ex);
            error++;
          }
        }
        log.info("Update of LDAP groups: "
            + (error > 0 ? "*** " + error + " errors ***, " : "")
            + unmodified
            + " unmodified, "
            + created
            + " created, "
            + updated
            + " updated, "
            + renamed
            + " renamed, "
            + deleted
            + " deleted.");
        log.info("LDAP update done.");
        return null;
      }
    }.excecute();
  }

  /**
   * Calls {@link LoginDefaultHandler#checkStayLoggedIn(PFUserDO)}.
   * @see org.projectforge.user.LoginHandler#checkStayLoggedIn(org.projectforge.user.PFUserDO)
   */
  @Override
  public boolean checkStayLoggedIn(final PFUserDO user)
  {
    final boolean result = loginDefaultHandler.checkStayLoggedIn(user);
    if (result == true && usersWithoutLdapPasswords.contains(user.getId()) == true) {
      log.info("User's stay-logged-in mechanism is temporarily disabled until the user re-logins via LoginForm to update his LDAP password (which isn't yet available): "
          + user.getUserDisplayname());
      return false;
    }
    return result;
  }

  /**
   * @see org.projectforge.user.LoginHandler#passwordChanged(org.projectforge.user.PFUserDO, java.lang.String)
   */
  @Override
  public void passwordChanged(final PFUserDO user, final String newPassword)
  {
    final LdapPerson ldapUser = ldapUserDao.findById(user.getId());
    if (ldapUser != null) {
      ldapUserDao.changePassword(ldapUser, null, newPassword);
    }
  }

  /**
   * @return always true because the change of passwords is supported for every user.
   * @see org.projectforge.user.LoginHandler#isPasswordChangeSupported(org.projectforge.user.PFUserDO)
   */
  @Override
  public boolean isPasswordChangeSupported(final PFUserDO user)
  {
    return true;
  }

  /**
   * @param updatedLdapGroup
   * @param assignedUsers
   * @param ldapUserMap
   */
  private void setMembers(final LdapGroup updatedLdapGroup, final Set<PFUserDO> assignedUsers, final Map<Integer, LdapPerson> ldapUserMap)
  {
    updatedLdapGroup.clearMembers();
    if (assignedUsers == null) {
      // No user to assign.
      return;
    }
    for (final PFUserDO assignedUser : assignedUsers) {
      final LdapPerson ldapUser = ldapUserMap.get(assignedUser.getId());
      if (ldapUser == null) {
        final PFUserDO cachedUser = Registry.instance().getUserGroupCache().getUser(assignedUser.getId());
        if (cachedUser == null || cachedUser.isDeleted() == false) {
          log.warn("Can't assign ldap user to group: "
              + updatedLdapGroup.getCommonName()
              + "! Ldap user with id '"
              + assignedUser.getId()
              + "' not found, skipping user.");
        }
      } else {
        if (assignedUser.hasSystemAccess() == true) {
          // Do not add deleted or deactivated users.
          updatedLdapGroup.addMember(ldapUser, baseDN);
        }
      }
    }
  }

  private Map<Integer, LdapPerson> getUserMap(final Collection<LdapPerson> users)
  {
    final Map<Integer, LdapPerson> map = new HashMap<Integer, LdapPerson>();
    if (users == null) {
      return map;
    }
    for (final LdapPerson user : users) {
      final Integer id = PFUserDOConverter.getId(user);
      if (id != null) {
        map.put(id, user);
      } else {
        log.warn("Given ldap user has no id (employee number), ignoring user for group assignments: " + user);
      }
    }
    return map;
  }

  private LdapPerson getLdapUser(final List<LdapPerson> ldapUsers, final PFUserDO user)
  {
    for (final LdapPerson ldapUser : ldapUsers) {
      if (StringUtils.equals(ldapUser.getUid(), user.getUsername()) == true
          || StringUtils.equals(ldapUser.getEmployeeNumber(), PFUserDOConverter.buildEmployeeNumber(user)) == true) {
        return ldapUser;
      }
    }
    return null;
  }

  private LdapGroup getLdapGroup(final List<LdapGroup> ldapGroups, final GroupDO group)
  {
    for (final LdapGroup ldapGroup : ldapGroups) {
      if (StringUtils.equals(ldapGroup.getBusinessCategory(), GroupDOConverter.buildBusinessCategory(group)) == true) {
        return ldapGroup;
      }
    }
    return null;
  }
}
