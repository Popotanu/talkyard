/**
 * Copyright (c) 2016 Kaj Magnus Lindberg
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

package controllers

import com.debiki.core._
import debiki.SiteTpi
import debiki.ReactJson._
import io.efdi.server.http._
import play.api._
import play.api.libs.json.Json


object SuperAdminController extends mvc.Controller {

  def redirect = GetAction { apiReq =>
    Redirect(routes.SuperAdminController.superAdminApp("").url)
  }


  def superAdminApp(clientRoute: String) = SuperAdminGetAction { apiReq =>
    _root_.controllers.dieIfAssetsMissingIfDevTest()

    if (!debiki.Globals.config.superAdmin.hostname.contains(apiReq.hostname))
      throwForbidden2("EsE2KG8U0", "Not a superadmin site")

    if (!apiReq.user.exists(_.isStaff)) {
      Ok(views.html.login.loginPopup(
        mode = "LoginToAdministrate",
        serverAddress = s"//${apiReq.host}",
        returnToUrl = apiReq.uri)) as HTML
    }
    else {
      val siteTpi = SiteTpi(apiReq)
      val pageBody = views.html.adminPage(siteTpi, appId = "theSuperAdminApp").body
      Ok(pageBody) as HTML
    }
  }


  def listSites() = SuperAdminGetAction { apiReq =>
    // The most recent first.
    val sites: Seq[Site] = debiki.Globals.systemDao.loadSites().sortBy(-_.createdAt.toUnixMillis)
    OkSafeJson(Json.obj(
      "appVersion" -> debiki.Globals.applicationVersion,
      "superadmin" -> Json.obj(
        "baseDomain" -> debiki.Globals.baseDomainWithPort,
        "sites" -> sites.map(siteToJson))))
  }


  def siteToJson(site: Site) = {
    Json.obj(
      "id" -> site.id,
      "canonicalHostname" -> JsStringOrNull(site.canonicalHost.map(_.hostname)),
      "name" -> site.name,
      "createdAtMs" -> site.createdAt.toUnixMillis)
  }

}
