/**
 * Copyright (C) 2012 Kaj Magnus Lindberg (born 1979)
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

package debiki

import controllers._
import com.debiki.v0._
import java.{util => ju}
import org.specs2.mutable._
import org.specs2.mock._
import Prelude._
import play.api.mvc.Request
import com.debiki.v0.{PostActionPayload => PAP}


class AutoApproverSpec extends Specification with Mockito {

  val startDati = new ju.Date(10 * 1000)
  val TenantId = "tenantid"
  val Ip = "1.1.1.1"
  val PageId = "pageid"

  val guestUser = User(
    id = "-guestid",
    displayName = "Guest Name",
    email = "guest.email@.com",
    emailNotfPrefs = null,
    country = "",
    website = "",
    isAdmin = false,
    isOwner = false)

  val guestIdty = IdentitySimple(
    id = guestUser.id drop 1, // drop "-"
    userId = guestUser.id,
    name = guestUser.displayName,
    email = guestUser.email,
    location = guestUser.country,
    website = guestUser.website)

  val openidUser = User(
    id = "openid",
    displayName = "Oid User Name",
    email = "oid.email@.com",
    emailNotfPrefs = null,
    country = "",
    website = "",
    isAdmin = false,
    isOwner = false)

  val openidIdty = IdentityOpenId(
    id = "oididtyid",
    userId = openidUser.id,
    oidEndpoint = "",
    oidVersion = "",
    oidRealm = "",
    oidClaimedId = "",
    oidOpLocalId = "",
    firstName = openidUser.displayName,
    email = openidUser.email,
    country = openidUser.country)

  val PlayReq = new Request[Unit] {
    def id = 12345
    def tags = Map.empty
    def uri = "uri"
    def path = "path"
    def method = "METHOD"
    def version = "1.1"
    def queryString = Map.empty
    def headers = null
    lazy val remoteAddress = Ip
    def username = None
    val body = ()
  }

  val pagePath = PagePath(
     tenantId = TenantId,
     folder = "/",
     pageId = Some(PageId),
     showId = true,
     pageSlug = "page-slug")

  def pageReq(user: User, identity: Identity)(dao: TenantDao) =
    PageRequest[Unit](
      sid = null,
      xsrfToken = null,
      identity = Some(identity),
      user = Some(user),
      pageExists = true,
      pagePath = pagePath,
      permsOnPage = PermsOnPage.All,
      dao = dao,
      request = PlayReq)()

  def pageReqOpenId = pageReq(openidUser, openidIdty) _
  def pageReqGuest = pageReq(guestUser, guestIdty) _

  val quotaConsumers = QuotaConsumers(
    tenantId = TenantId, ip = Some(Ip), roleId = None)

  val peopleNoLogins =
    People() + guestIdty + openidIdty + guestUser + openidUser

  val loginId = "101"

  val body =
    PostActionDto.forNewPageBody(creationDati = startDati,
      loginId = loginId, userId = "?", text = "täxt-tåxt",
      pageRole = PageRole.Generic, approval = None)

  val replyA = PostActionDto.copyCreatePost(body, id = 2, parentPostId = body.id)
  val replyB = PostActionDto.copyCreatePost(body, id = 3, parentPostId = body.id)

  val (guestLogin, openidLogin) = {
    val login = Login(id = loginId, ip = Ip, prevLoginId = None,
       date = startDati, identityId = "?")
    (login.copy(identityId = guestIdty.id),
      login.copy(identityId = openidIdty.id))
  }

  def newDaoMock(actionDtos: List[PostActionDto[PAP.CreatePost]], login: Login) = {

    val actions: Seq[PostActionOld] = {
      val page = PageParts("pageid") ++ actionDtos
      actionDtos map (new Post(page, _))
    }

    val people =
      if (actionDtos nonEmpty) peopleNoLogins + login
      else People.None

    val daoMock = mock[TenantDao]
    daoMock.tenantId returns TenantId
    daoMock.quotaConsumers returns quotaConsumers

    daoMock.loadRecentActionExcerpts(
      fromIp = Some(Ip),
      limit = AutoApprover.RecentActionsLimit)
       .returns(actions -> people)

    daoMock.loadRecentActionExcerpts(
      byIdentity = Some(guestIdty.id),
      limit = AutoApprover.RecentActionsLimit)
       .returns(actions -> people)

    daoMock
  }


  "AutoApprover" can {

    "approve an admin's comments" >> {
      pending
    }


    "approve a user's first comments preliminarily" >> {

      "the first one" >> {
        AutoApprover.perhapsApprove(pageReqGuest(newDaoMock(
          Nil, null))) must_== Some(Approval.Preliminary)
      }

      "the second" >> {
        // SHOULD prel approve replyA.
        AutoApprover.perhapsApprove(pageReqGuest(newDaoMock(
          List(replyA).map(_.copy(userId = guestUser.id)), guestLogin))
          ) must_== Some(Approval.Preliminary)
      }

      "but not the third one" >> {
        // SHOULD prel approve replyA and B.
        AutoApprover.perhapsApprove(pageReqGuest(newDaoMock(
          List(replyA, replyB).map(_.copy(userId = guestUser.id)), guestLogin))
          ) must_== None
      }
    }


    "approve a well behaved user's comments, preliminarily" >> {

      "if many comments have already been approved" >> {
        pending
      }

      "unless too many unreviewed comments, from that user" >> {
        pending
      }

      "unless too many unreviewed comments, from all users" >> {
        pending
      }
    }


    "queue comment for moderation" >> {

      "if any recent comment rejected" >> {
        pending
      }

      "if any recent comment flagged" >> {
        pending
      }
    }


    "throw Forbidden response" >> {

      "if a spammer has fairly many comments pending moderation" >> {
        pending
      }

      "if a well behaved user has terribly many comments pending" >> {
        pending
      }
    }

  }

}


