package org.folio.okapi.bean;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.vertx.core.json.Json;
import io.vertx.core.json.DecodeException;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;

/**
 * One entry in Okapi's routing table.
 * Each entry contains one or more HTTP methods, and the path they mean,
 * for example "GET /foo". Incoming requests are mapped to a series of
 * routingEntries, ordered by their level. Also carries the permission bits
 * required and desired for this operation.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RoutingEntry {
  private final Logger logger = LoggerFactory.getLogger("okapi");

  private String[] methods;
  private String pathPattern;
  private String path;
  private String phase;
  private String level;
  private String type;
  private String redirectPath; // only for type='redirect'
  private String[] permissionsRequired;
  private String[] permissionsDesired;
  private String[] modulePermissions;
  private static final String INVALID_PATH_CHARS = "\\%+{}()[].;:=?@#^$\"' ";
  @JsonIgnore
  private String pathRegex;
  @JsonIgnore
  private String phaseLevel = "50";

  public enum ProxyType {
    REQUEST_RESPONSE,
    REQUEST_ONLY,
    HEADERS,
    REDIRECT
  };

  @JsonIgnore
  private ProxyType proxyType = ProxyType.REQUEST_RESPONSE;

  public String[] getPermissionsRequired() {
    return permissionsRequired;
  }

  public void setPermissionsRequired(String[] permissionsRequired) {
    this.permissionsRequired = permissionsRequired;
  }

  public String[] getPermissionsDesired() {
    return permissionsDesired;
  }

  public void setPermissionsDesired(String[] permissionsDesired) {
    this.permissionsDesired = permissionsDesired;
  }

  public String[] getModulePermissions() {
    return modulePermissions;
  }

  public void setModulePermissions(String[] modulePermissions) {
    this.modulePermissions = modulePermissions;
  }

  @JsonIgnore
  public ProxyType getProxyType() {
    return this.proxyType;
  }

  public String getType() {
    return type;
  }

  public void setType(String type) {
    if ("request-response".equals(type)) {
      proxyType = ProxyType.REQUEST_RESPONSE;
    } else if ("request-only".equals(type)) {
      proxyType = ProxyType.REQUEST_ONLY;
    } else if ("headers".equals(type)) {
      proxyType = ProxyType.HEADERS;
    } else if ("redirect".equals(type)) {
      proxyType = ProxyType.REDIRECT;
    } else if ("system".equals(type)) {
      proxyType = ProxyType.REQUEST_RESPONSE;
    } else {
      throw new DecodeException("Invalid entry type: " + type);
    }
    this.type = type;
  }

  public String getRedirectPath() {
    return redirectPath;
  }

  public void setRedirectPath(String redirectPath) {
    this.redirectPath = redirectPath;
  }

  public String getLevel() {
    return level;
  }

  public void setLevel(String level) {
    this.level = level;
    this.phaseLevel = level;
  }

  @JsonIgnore
  public String getPhaseLevel() {
    return phaseLevel;
  }

  public String[] getMethods() {
    return methods;
  }

  public void setMethods(String[] methods) {
    this.methods = methods;
  }

  public String getPath() {
    return path;
  }

  public void setPath(String path) {
    this.path = path;
  }

  public String getPathPattern() {
    return pathPattern;
  }

  public void setPathPattern(String pathPattern) throws DecodeException {
    this.pathPattern = pathPattern;
    StringBuilder b = new StringBuilder();
    b.append("^");
    for (int i = 0; i < pathPattern.length(); i++) {
      char c = pathPattern.charAt(i);
      if (c == '{') {
        b.append("[^/?#]+");
        i++;
        for (; i < pathPattern.length(); i++) {
          c = pathPattern.charAt(i);
          if (c == '}') {
            break;
          } else if (INVALID_PATH_CHARS.indexOf(c) != -1 || c == '/') {
            throw new DecodeException("Invalid character " + c + " inside {}-construct in pathPattern");
          }
        }
        if (c != '}') {
          throw new DecodeException("Missing {-character for {}-construct in pathPattern");
        }
      } else if (c == '*') {
        b.append(".*");
      } else if (INVALID_PATH_CHARS.indexOf(c) != -1) {
        throw new DecodeException("Invalid character " + c + " for pathPattern");
      } else {
        b.append(c);
      }
    }
    b.append("$");
    this.pathRegex = b.toString();
  }

  public boolean match(String uri, String method) {
    if (pathRegex != null) {
      String p = uri;
      int indx = p.indexOf('?');
      if (indx > 0) {
        p = p.substring(0, indx);
      }
      indx = p.indexOf('#');
      if (indx > 0) {
        p = p.substring(0, indx);
      }
      if (!p.matches(pathRegex)) {
        return false;
      }
    } else if (path != null) {
      if (!uri.startsWith(path)) {
        return false;
      }
    }
    for (String m : methods) {
      if (method == null || m.equals("*") || m.equals(method)) {
        return true;
      }
    }
    return false;
  }

  public String getRedirectUri(String uri) {
    if (pathRegex != null) {
      int indx1 = uri.indexOf('?');
      final int indx2 = uri.indexOf('#');
      if (indx1 == -1) {
        indx1 = indx2;
      }
      String p;
      if (indx1 != -1) {
        p = uri.substring(0, indx1);
      } else {
        p = uri;
      }
      p = p.replaceAll(pathRegex, this.redirectPath);
      if (indx1 != -1) {
        p = p.concat(uri.substring(indx1));
      }
      return p;
    } else if (path != null) {
      return redirectPath + uri.substring(path.length());
    } else {
      return null;
    }
  }
  /**
   * Validate the RoutingEntry.
   *
   * @param strict - if false, will not report all error, just log a warning
   * @param section "provides" or "toplevel". Soon also "filter" and "handler".
   * @return an error message (as a string), or "" if all is well.
   */
  public String validate(boolean strict, String section) {
    logger.debug("Validating RoutingEntry " + Json.encode(this));
    if ((path == null || path.isEmpty())
      && (pathPattern == null || pathPattern.isEmpty())) {
      return "Bad routing entry, needs a pathPattern or at least a path";
    }

    // TODO - Do not accept old paths in Handlers and Filters, once we get those
    if (pathPattern == null || pathPattern.isEmpty()) {
      logger.warn("RoutingEntry uses old type path " + path
        + ". Use a pathPattern instead");
    }

    // TODO - Validate permissions required and desired, and modulePerms
    return ""; // no problems found
  }

  public String getPhase() {
    return phase;
  }

  public void setPhase(String phase) {
    if ("auth".equals(phase)) {
      phaseLevel = "10";
    } else {
      throw new DecodeException("Invalid phase " + phase);
    }
    this.phase = phase;
  }
}
