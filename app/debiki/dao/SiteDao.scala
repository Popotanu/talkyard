/**
 * Copyright (c) 2012-2016 Kaj Magnus Lindberg
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package debiki.dao

import com.debiki.core._
import com.debiki.core.Prelude._
import debiki._
import ed.server.search.SearchEngine
import ed.server.http._
import org.{elasticsearch => es}
import redis.RedisClient
import scala.collection.mutable
import SiteDao._
import ed.server.auth.MayMaybe
import ed.server.pop.PagePopularityDao
import ed.server.pubsub.{PubSubApi, StrangerCounterApi}
import ed.server.summaryemails.SummaryEmailsDao



class SiteDaoFactory (
  private val _dbDaoFactory: DbDaoFactory,
  private val redisClient: RedisClient,
  private val cache: DaoMemCache,
  private val usersOnlineCache: UsersOnlineCache,
  private val elasticSearchClient: es.client.Client,
  private val config: Config) {

  def newSiteDao(siteId: SiteId): SiteDao = {
    new SiteDao(siteId, _dbDaoFactory, redisClient, cache, usersOnlineCache, elasticSearchClient,
      config)
  }

}



/** A data access object for site specific data. Data could be loaded
  * from database, or fetched from some in-memory cache.
  *
  * Don't use for more than one http request — it might cache things,
  * in private fields, and is perhaps not thread safe.
  *
  * Naming convention: dao.getWhatever() when a cache (in-process or Redis) is used.
  * But dao.loadWhatever() when no cache is used, the db is always accessed.
  * And dao.theWhatever(), when the cache is used, and the Whatever must exist otherwise
  * a runtime exception will be thrown.
  * And dao.loadTheWhatever() when the cache is not used, and the whatever must exist.
  * COULD REFACTOR RENAME according to above naming convention.
  */
class SiteDao(
  val siteId: SiteId,
  private val dbDaoFactory: DbDaoFactory,
  private val redisClient: RedisClient,
  private val cache: DaoMemCache,
  val usersOnlineCache: UsersOnlineCache,
  private val elasticSearchClient: es.client.Client,
  val config: Config)
  extends AnyRef
  with AssetBundleDao
  with SettingsDao
  with SpecialContentDao
  with ed.server.auth.AuthzSiteDaoMixin
  with ForumDao
  with CategoriesDao
  with PagesDao
  with PagePathMetaDao
  with PageStuffDao
  with PagePopularityDao
  with RenderedPageHtmlDao
  with PostsDao
  with TagsDao
  with SearchDao
  with ed.server.spam.QuickSpamCheckDao
  with UploadsDao
  with UserDao
  with MessagesDao
  with WatchbarDao
  with ReviewsDao
  with SummaryEmailsDao
  with AuditDao {

  protected lazy val memCache = new MemCache(siteId, cache)
  protected lazy val redisCache = new RedisCache(siteId, redisClient)
  protected lazy val searchEngine = new SearchEngine(siteId, elasticSearchClient)


  def memCache_test: MemCache = {
    require(Globals.isOrWasTest, "EsE7YKP42B")
    memCache
  }


  private def dbDao2: DbDao2 = dbDaoFactory.newDbDao2()

  def commonmarkRenderer = ReactRenderer


  memCache.onUserCreated { user =>
    if (theSite().status == SiteStatus.NoAdmin) {
      dieIf(!user.isOwner, "EsE6YK20")
      dieIf(!user.isAdmin, "EsE2KU80")
      uncacheSite()
    }
  }

  private def uncacheSite() {
    val thisSite = memCache.lookup[Site](thisSiteCacheKey)
    memCache.remove(thisSiteCacheKey)
    thisSite.foreach(SystemDao.removeCanonicalHostCacheEntries(_, memCache))
  }

  private def thisSiteCacheKey = siteCacheKey(this.siteId)


  // Rename to ...NoRetry, add readWriteTransactionWithRetry
  def readWriteTransaction[R](fn: SiteTransaction => R, allowOverQuota: Boolean = false): R = {
    // Serialize writes per site. This avoids all? transaction rollbacks because of
    // serialization errors in Postgres (e.g. if 2 people post 2 comments at the same time).
    // Later: Send a message to a per-site actor instead which handles all writes for that site,
    // one at a time. Wait for a reply for at most ... 1? 5? (Right now we might block
    // forever though, bad bad bad.)
    SECURITY // this makes a DoS attack possible? By posting comments all the time, one can make
    // all threads block, waiting for the per-site lock. There's rate limiting stuff though
    // so doing this takes some effort.
    synchronizeOnSiteId(siteId) {
      dbDao2.readWriteSiteTransaction(siteId, allowOverQuota) {
        fn(_)
      }
      // If serialization error, try once? twice? again?
    }
  }

  def readOnlyTransaction[R](fn: SiteTransaction => R): R =
    dbDao2.readOnlySiteTransaction(siteId, mustBeSerializable = true) { fn(_) }

  def readOnlyTransactionNotSerializable[R](fn: SiteTransaction => R): R =
    dbDao2.readOnlySiteTransaction(siteId, mustBeSerializable = false) { fn(_) }


  def refreshPageInMemCache(pageId: PageId) {
    memCache.firePageSaved(SitePageId(siteId = siteId, pageId = pageId))
  }

  def refreshPagesInAnyCache(pageIds: collection.Set[PageId]) {
    pageIds.foreach(refreshPageInMemCache)
  }

  def emptyCache() {
    readWriteTransaction(_.bumpSiteVersion())
    memCache.clearSingleSite(siteId)
  }


  def emptyCacheImpl(transaction: SiteTransaction) {
    transaction.bumpSiteVersion()
    memCache.clearSingleSite(siteId)
  }


  def removeFromMemCache(key: MemCacheKey) {
    memCache.remove(key)
  }


  def dieOrThrowNoUnless(mayMaybe: MayMaybe, errorCode: String) {
    COULD // avoid logging harmless internal error, see comment below,
    // by checking Globals.now() - this-dao.createdAt and doing throwForbidden() not die().
    // Later, check if current time minus request start time is small, then just
    // throw 403 Forbidden (instead of die()) because then the permission error is probably
    // becaues of a harmless race condition (namely an admin changed permissions just after
    // a perm check at the start of a request was done, but before the perm check inside
    // a transaction was done, resulting in first "you may, yes" and then "no you may not").
    // But for now, always die:
    import MayMaybe._
    mayMaybe match {
      case Yes => // fine
      case NoNotFound(debugCode) => throwIndistinguishableNotFound(s"$errorCode-$debugCode")
      case mayNot: NoMayNot => die(errorCode, s"${mayNot.reason} [${mayNot.code}]")
    }
  }


  // ----- Site

  def theSite(): Site = getSite().getOrDie("DwE5CB50", s"Site $siteId not found")

  /** Uses the hostname, if no name available. Well currently always uses the hostname.
    */
  def theSiteName(): String = theSiteNameAndOrigin()._1

  def theSiteNameAndOrigin(): (String, String) = {
    val site = theSite()
    val anyPrettyHostname = site.canonicalHost.map(_.hostname)
    val anyPrettyOrigin = site.canonicalHost.map(Globals.schemeColonSlashSlash + _.hostname)
    val siteNameOrHostname = anyPrettyHostname getOrElse site.name
    val origin = anyPrettyOrigin getOrElse Globals.siteByIdOrigin(siteId)
    (siteNameOrHostname, origin)
  }

  def getSite(): Option[Site] = {
    memCache.lookup(
      thisSiteCacheKey,
      orCacheAndReturn = loadSiteNoCache())
  }

  private def loadSiteNoCache(): Option[Site] = {
    var site = readOnlyTransaction(_.loadSite()) getOrElse {
      return None
    }
    if (siteId == FirstSiteId && site.canonicalHost.isEmpty) {
      // No hostname specified in the database. Fallback to the config file.
      val hostname = Globals.firstSiteHostname.getOrDie(
        "EsE5GKU2", s"No ${Globals.FirstSiteHostnameConfigValue} specified")
      val canonicalHost = SiteHost(hostname, SiteHost.RoleCanonical)
      site = site.copy(hosts = canonicalHost :: site.hosts)
    }
    Some(site)
  }

  def ensureSiteActiveOrThrow(newMember: MemberInclDetails, transaction: SiteTransaction) {
    // The throwForbidden exceptions can be triggered for example if someone starts signing up,
    // then the site gets deleted, and then the person clicks the submit button in
    // the signup form. (I.e. a race condition, and that's fine.)
    val site = transaction.loadSite().getOrDie("EsE5YKW0", s"Site gone: ${transaction.siteId}")
    site.status match {
      case SiteStatus.NoAdmin =>
        // We're creating an admin, therefore the site should now be activated.
        dieIf(!newMember.isOwner, "EsE5KYF0", "Trying to create a non-owner for a NoAdmin site")
        dieIf(!newMember.isAdmin, "EsE7RU82", "Trying to create a non-admin for a NoAdmin site")
        transaction.updateSite(
          site.copy(status = SiteStatus.Active))
        BUG; RACE // if reloaded before transaction committed, old state will be reinserted
        // into the cache. Have the caller call uncacheSite() instead? But how ensure it'll
        // remember to do that??
        uncacheSite()
      case SiteStatus.Active =>
        // Fine.
      case SiteStatus.ReadAndCleanOnly =>
        if (!newMember.isStaff)
          throwForbidden2("EsE3KUG54", o"""Trying to create a non-staff user for
              a ${site.status} site""")
      case SiteStatus.HiddenUnlessAdmin =>
        if (!newMember.isAdmin)
          throwForbidden2("EsE9YK24S", o"""Trying to create a non-admin user for
              a ${site.status} site""")
      case _ =>
        dieUnless(site.status.isDeleted, "EsE4FEI29")
        throwForbidden2("EsE5KUFW2", "This site has been deleted. Cannot add new users.")
    }
  }

  def updateSite(changedSite: Site) {
    readWriteTransaction(_.updateSite(changedSite))
    uncacheSite()
  }

  def listHostnames(): Seq[SiteHost] = {
    readOnlyTransaction(_.listHostnames())
  }

  def changeSiteHostname(newHostname: String) {
    readWriteTransaction { transaction =>
      val site = transaction.loadSite() getOrDie "EsE2PK4Y8X"
      if (site.hosts.sortBy(_.hostname).length > MaxOldHostnames) {
        // COULD check last week? month? only, and show a warning before forbidding.
        throwForbidden2("EsE3KYP2", "You've changed hostname too many times")
      }
      transaction.changeCanonicalHostRoleToExtra()
      try transaction.insertSiteHost(SiteHost(newHostname, SiteHost.RoleCanonical))
      catch {
        case _: DuplicateHostnameException =>
          throwForbidden2("EdE7FKW20", s"There's already a site with hostname '$newHostname'")
      }
      uncacheSite()
    }
  }

  def changeExtraHostsRole(newRole: SiteHost.Role) {
    readWriteTransaction { transaction =>
      transaction.changeExtraHostsRole(newRole)
      uncacheSite()
    }
  }

  def loadResourceUsage(): ResourceUse = {
    readWriteTransaction { transaction =>
      transaction.loadResourceUsage()
    }
  }


  // ----- List stuff

  def listPagePaths(
        pageRanges: PathRanges,
        include: List[PageStatus],
        orderOffset: PageOrderOffset,
        limit: Int): Seq[PagePathAndMeta] =
    readOnlyTransaction(_.listPagePaths(pageRanges, include, orderOffset, limit))


  // ----- Notifications

  def pubSub: PubSubApi = Globals.pubSub
  def strangerCounter: StrangerCounterApi = Globals.strangerCounter

  def saveDeleteNotifications(notifications: Notifications) {
    readWriteTransaction(_.saveDeleteNotifications(notifications))
  }

  def loadNotificationsForRole(roleId: RoleId, limit: Int, unseenFirst: Boolean)
        : Seq[Notification] =
    readOnlyTransaction(_.loadNotificationsForRole(roleId, limit, unseenFirst))

  def updateNotificationSkipEmail(notifications: Seq[Notification]): Unit =
    readWriteTransaction(_.updateNotificationSkipEmail(notifications))

  def markNotificationAsSeen(userId: UserId, notfId: NotificationId): Unit =
    readWriteTransaction(_.markNotfAsSeenSkipEmail(userId, notfId))


  // ----- Emails

  def saveUnsentEmail(email: Email): Unit =
    readWriteTransaction(_.saveUnsentEmail(email))

  def saveUnsentEmailConnectToNotfs(email: Email, notfs: Seq[Notification]): Unit =
    readWriteTransaction(_.saveUnsentEmailConnectToNotfs(email, notfs))

  def updateSentEmail(email: Email) {
    readWriteTransaction { transaction =>
      transaction.updateSentEmail(email)
      if (email.failureText.isEmpty) {
        email.toUserId foreach { userId =>
          addUserStats(UserStats(
            userId, lastEmailedAt = When.fromOptDate(email.sentOn)))(transaction)
        }
      }
    }
  }

  def loadEmailById(emailId: String): Option[Email] =
    readOnlyTransaction(_.loadEmailById(emailId))

}



object SiteDao {

  private val MaxOldHostnames = 5  // dupl in JS [7GK8W2Z]

  private val locksBySiteId = mutable.HashMap[SiteId, Object]()

  def siteCacheKey(siteId: SiteId) = MemCacheKey(siteId, "|SiteId")

  def synchronizeOnSiteId[R](siteId: SiteId)(block: => R): R = {
    val lock = locksBySiteId.getOrElseUpdate(siteId, new Object)
    lock.synchronized {
      block
    }
  }

}
