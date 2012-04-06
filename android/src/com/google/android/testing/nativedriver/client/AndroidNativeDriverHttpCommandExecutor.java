package com.google.android.testing.nativedriver.client;
import static org.apache.http.protocol.ExecutionContext.HTTP_TARGET_HOST;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.BindException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Map;

import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpHost;
import org.apache.http.HttpResponse;
import org.apache.http.NoHttpResponseException;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.client.params.HttpClientParams;
import org.apache.http.conn.ClientConnectionManager;
import org.apache.http.conn.scheme.Scheme;
import org.apache.http.conn.scheme.SchemeRegistry;
import org.apache.http.conn.ssl.SSLSocketFactory;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.impl.conn.SingleClientConnManager;
import org.apache.http.params.BasicHttpParams;
import org.apache.http.params.CoreConnectionPNames;
import org.apache.http.params.HttpParams;
import org.apache.http.protocol.BasicHttpContext;
import org.apache.http.protocol.HttpContext;
import org.apache.http.util.EntityUtils;
import org.openqa.selenium.WebDriverException;
import org.openqa.selenium.net.Urls;
import org.openqa.selenium.remote.BeanToJsonConverter;
import org.openqa.selenium.remote.Command;
import org.openqa.selenium.remote.CommandExecutor;
import org.openqa.selenium.remote.DriverCommand;
import org.openqa.selenium.remote.ErrorCodes;
import org.openqa.selenium.remote.HttpCommandExecutor;
import org.openqa.selenium.remote.JsonToBeanConverter;
import org.openqa.selenium.remote.Response;
import org.openqa.selenium.remote.ReusingSocketSocketFactory;
import org.openqa.selenium.remote.SessionId;

import com.google.android.testing.nativedriver.client.internal.HttpClientFactory;
import com.google.android.testing.nativedriver.common.AndroidNativeDriverCommand;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableMap;

public class AndroidNativeDriverHttpCommandExecutor implements CommandExecutor
{
  private static final int MAX_REDIRECTS = 10;

  private final HttpHost targetHost;
  private final URL remoteServer;
  private final Map<String, CommandInfo> nameToUrl;
  private final HttpClient client;

  private static ClientConnectionManager getClientConnectionManager(HttpParams httpParams)
  {
    SchemeRegistry registry = new SchemeRegistry();
    registry.register(new Scheme("http", ReusingSocketSocketFactory.getSocketFactory(), 80));
    registry.register(new Scheme("https", SSLSocketFactory.getSocketFactory(), 443));
    return new SingleClientConnManager(httpParams, registry);
  }
  
  private static HttpClientFactory httpClientFactory;

  private enum HttpVerb {
    GET() {
      @Override
      public HttpUriRequest createMethod(String url) {
        return new HttpGet(url);
      }
    },
    POST() {
      @Override
      public HttpUriRequest createMethod(String url) {
        return new HttpPost(url);
      }
    },
    DELETE() {
      @Override
      public HttpUriRequest createMethod(String url) {
        return new HttpDelete(url);
      }
    };

    public abstract HttpUriRequest createMethod(String url);
  }

  public AndroidNativeDriverHttpCommandExecutor(URL addressOfRemoteServer) {
    try {
      remoteServer = addressOfRemoteServer == null ?
                     new URL(System.getProperty("webdriver.remote.server")) :
                     addressOfRemoteServer;
    } catch (MalformedURLException e) {
      throw new WebDriverException(e);
    }

    HttpParams params = new BasicHttpParams();
    // Use the JRE default for the socket linger timeout.
    params.setParameter(CoreConnectionPNames.SO_LINGER, -1);
    HttpClientParams.setRedirecting(params, false);

    synchronized (HttpCommandExecutor.class) {
      if (httpClientFactory == null) {
        httpClientFactory = new HttpClientFactory();
      }
    }
//    client = httpClientFactory.getHttpClient();
    this.client = new DefaultHttpClient(getClientConnectionManager(params), params);
    if (addressOfRemoteServer != null && addressOfRemoteServer.getUserInfo() != null) {
      // Use HTTP Basic auth
      UsernamePasswordCredentials credentials = new
          UsernamePasswordCredentials(addressOfRemoteServer.getUserInfo());
      ((DefaultHttpClient) client).getCredentialsProvider().
          setCredentials(AuthScope.ANY, credentials);
    }

    // Some machines claim "localhost.localdomain" is the same as "localhost".
    // This assumption is not always true.

    String host = remoteServer.getHost().replace(".localdomain", "");

    targetHost = new HttpHost(
        host, remoteServer.getPort(), remoteServer.getProtocol());

//    nameToUrl = ImmutableMap.<String, CommandInfo>builder()
//        .put(AndroidNativeDriverCommand.NEW_SESSION, post("/session"))
//        .put(AndroidNativeDriverCommand.QUIT, delete("/session/:sessionId"))
//        .put(AndroidNativeDriverCommand.GET_CURRENT_WINDOW_HANDLE, get("/session/:sessionId/window_handle"))
//        .put(AndroidNativeDriverCommand.GET_WINDOW_HANDLES, get("/session/:sessionId/window_handles"))
//        .put(AndroidNativeDriverCommand.GET, post("/session/:sessionId/url"))
//
//            // The Alert API is still experimental and should not be used.
////        .put(AndroidNativeDriverCommand.GET_ALERT, get("/session/:sessionId/alert"))
//        .put(AndroidNativeDriverCommand.DISMISS_ALERT, post("/session/:sessionId/dismiss_alert"))
//        .put(AndroidNativeDriverCommand.ACCEPT_ALERT, post("/session/:sessionId/accept_alert"))
//        .put(AndroidNativeDriverCommand.GET_ALERT_TEXT, get("/session/:sessionId/alert_text"))
//        .put(AndroidNativeDriverCommand.SET_ALERT_VALUE, post("/session/:sessionId/alert_text"))
//
//        .put(AndroidNativeDriverCommand.GO_FORWARD, post("/session/:sessionId/forward"))
//        .put(AndroidNativeDriverCommand.GO_BACK, post("/session/:sessionId/back"))
//        .put(AndroidNativeDriverCommand.REFRESH, post("/session/:sessionId/refresh"))
//        .put(AndroidNativeDriverCommand.EXECUTE_SCRIPT, post("/session/:sessionId/execute"))
//        .put(AndroidNativeDriverCommand.EXECUTE_ASYNC_SCRIPT, post("/session/:sessionId/execute_async"))
//        .put(AndroidNativeDriverCommand.GET_CURRENT_URL, get("/session/:sessionId/url"))
//        .put(AndroidNativeDriverCommand.GET_TITLE, get("/session/:sessionId/title"))
//        .put(AndroidNativeDriverCommand.GET_PAGE_SOURCE, get("/session/:sessionId/source"))
//        .put(AndroidNativeDriverCommand.SCREENSHOT, get("/session/:sessionId/screenshot"))
//        .put(AndroidNativeDriverCommand.SET_BROWSER_VISIBLE, post("/session/:sessionId/visible"))
//        .put(AndroidNativeDriverCommand.IS_BROWSER_VISIBLE, get("/session/:sessionId/visible"))
//        .put(AndroidNativeDriverCommand.FIND_ELEMENT, post("/session/:sessionId/element"))
//        .put(AndroidNativeDriverCommand.FIND_ELEMENTS, post("/session/:sessionId/elements"))
//        .put(AndroidNativeDriverCommand.GET_ACTIVE_ELEMENT, post("/session/:sessionId/element/active"))
//        .put(AndroidNativeDriverCommand.FIND_CHILD_ELEMENT, post("/session/:sessionId/element/:id/element"))
//        .put(AndroidNativeDriverCommand.FIND_CHILD_ELEMENTS, post("/session/:sessionId/element/:id/elements"))
//        .put(AndroidNativeDriverCommand.CLICK_ELEMENT, post("/session/:sessionId/element/:id/click"))
//        .put(AndroidNativeDriverCommand.CLEAR_ELEMENT, post("/session/:sessionId/element/:id/clear"))
//        .put(AndroidNativeDriverCommand.SUBMIT_ELEMENT, post("/session/:sessionId/element/:id/submit"))
//        .put(AndroidNativeDriverCommand.GET_ELEMENT_TEXT, get("/session/:sessionId/element/:id/text"))
//        .put(AndroidNativeDriverCommand.SEND_KEYS_TO_ELEMENT, post("/session/:sessionId/element/:id/value"))
////        .put(AndroidNativeDriverCommand.UPLOAD_FILE, post("/session/:sessionId/file"))
//        .put(AndroidNativeDriverCommand.GET_ELEMENT_VALUE, get("/session/:sessionId/element/:id/value"))
//        .put(AndroidNativeDriverCommand.GET_ELEMENT_TAG_NAME, get("/session/:sessionId/element/:id/name"))
//        .put(AndroidNativeDriverCommand.IS_ELEMENT_SELECTED, get("/session/:sessionId/element/:id/selected"))
//        .put(AndroidNativeDriverCommand.IS_ELEMENT_ENABLED, get("/session/:sessionId/element/:id/enabled"))
//        .put(AndroidNativeDriverCommand.IS_ELEMENT_DISPLAYED, get("/session/:sessionId/element/:id/displayed"))
//        .put(AndroidNativeDriverCommand.HOVER_OVER_ELEMENT, post("/session/:sessionId/element/:id/hover"))
//        .put(AndroidNativeDriverCommand.GET_ELEMENT_LOCATION, get("/session/:sessionId/element/:id/location"))
//        .put(AndroidNativeDriverCommand.GET_ELEMENT_LOCATION_ONCE_SCROLLED_INTO_VIEW,
//             get("/session/:sessionId/element/:id/location_in_view"))
//        .put(AndroidNativeDriverCommand.GET_ELEMENT_SIZE, get("/session/:sessionId/element/:id/size"))
//        .put(AndroidNativeDriverCommand.GET_ELEMENT_ATTRIBUTE, get("/session/:sessionId/element/:id/attribute/:name"))
//        .put(AndroidNativeDriverCommand.ELEMENT_EQUALS, get("/session/:sessionId/element/:id/equals/:other"))
//        .put(AndroidNativeDriverCommand.GET_ALL_COOKIES, get("/session/:sessionId/cookie"))
//        .put(AndroidNativeDriverCommand.ADD_COOKIE, post("/session/:sessionId/cookie"))
//        .put(AndroidNativeDriverCommand.DELETE_ALL_COOKIES, delete("/session/:sessionId/cookie"))
//        .put(AndroidNativeDriverCommand.DELETE_COOKIE, delete("/session/:sessionId/cookie/:name"))
//        .put(AndroidNativeDriverCommand.SWITCH_TO_FRAME, post("/session/:sessionId/frame"))
//        .put(AndroidNativeDriverCommand.SWITCH_TO_WINDOW, post("/session/:sessionId/window"))
////        .put(AndroidNativeDriverCommand.GET_WINDOW_SIZE, get("/session/:sessionId/window/:windowHandle/size"))
////        .put(AndroidNativeDriverCommand.GET_WINDOW_POSITION, get("/session/:sessionId/window/:windowHandle/position"))
////        .put(AndroidNativeDriverCommand.SET_WINDOW_SIZE, post("/session/:sessionId/window/:windowHandle/size"))
////        .put(AndroidNativeDriverCommand.SET_WINDOW_POSITION, post("/session/:sessionId/window/:windowHandle/position"))
//        .put(AndroidNativeDriverCommand.CLOSE, delete("/session/:sessionId/window"))
//        .put(AndroidNativeDriverCommand.DRAG_ELEMENT, post("/session/:sessionId/element/:id/drag"))
//        .put(AndroidNativeDriverCommand.GET_ELEMENT_VALUE_OF_CSS_PROPERTY,
//             get("/session/:sessionId/element/:id/css/:propertyName"))
//        .put(AndroidNativeDriverCommand.IMPLICITLY_WAIT, post("/session/:sessionId/timeouts/implicit_wait"))
//        .put(AndroidNativeDriverCommand.SET_SCRIPT_TIMEOUT, post("/session/:sessionId/timeouts/async_script"))
////        .put(AndroidNativeDriverCommand.SET_TIMEOUT, post("/session/:sessionId/timeouts"))
//        .put(AndroidNativeDriverCommand.EXECUTE_SQL, post("/session/:sessionId/execute_sql"))
//        .put(AndroidNativeDriverCommand.GET_LOCATION, get("/session/:sessionId/location"))
//        .put(AndroidNativeDriverCommand.SET_LOCATION, post("/session/:sessionId/location"))
//        .put(AndroidNativeDriverCommand.GET_APP_CACHE, get("/session/:sessionId/application_cache"))
//        .put(AndroidNativeDriverCommand.GET_APP_CACHE_STATUS, get("/session/:sessionId/application_cache/status"))
////        .put(AndroidNativeDriverCommand.CLEAR_APP_CACHE, delete("/session/:sessionId/application_cache/clear"))
//        .put(AndroidNativeDriverCommand.IS_BROWSER_ONLINE, get("/session/:sessionId/browser_connection"))
//        .put(AndroidNativeDriverCommand.SET_BROWSER_ONLINE, post("/session/:sessionId/browser_connection"))
//
//            // TODO (user): Would it be better to combine this command with
//            // GET_LOCAL_STORAGE_SIZE?
//        .put(AndroidNativeDriverCommand.GET_LOCAL_STORAGE_ITEM, get("/session/:sessionId/local_storage/key/:key"))
//        .put(AndroidNativeDriverCommand.REMOVE_LOCAL_STORAGE_ITEM, delete("/session/:sessionId/local_storage/key/:key"))
//        .put(AndroidNativeDriverCommand.GET_LOCAL_STORAGE_KEYS, get("/session/:sessionId/local_storage"))
//        .put(AndroidNativeDriverCommand.SET_LOCAL_STORAGE_ITEM, post("/session/:sessionId/local_storage"))
//        .put(AndroidNativeDriverCommand.CLEAR_LOCAL_STORAGE, delete("/session/:sessionId/local_storage"))
//        .put(AndroidNativeDriverCommand.GET_LOCAL_STORAGE_SIZE, get("/session/:sessionId/local_storage/size"))
//
//            // TODO (user): Would it be better to combine this command with
//            // GET_SESSION_STORAGE_SIZE?
//        .put(AndroidNativeDriverCommand.GET_SESSION_STORAGE_ITEM, get("/session/:sessionId/session_storage/key/:key"))
//        .put(AndroidNativeDriverCommand.REMOVE_SESSION_STORAGE_ITEM, delete("/session/:sessionId/session_storage/key/:key"))
//        .put(AndroidNativeDriverCommand.GET_SESSION_STORAGE_KEYS, get("/session/:sessionId/session_storage"))
//        .put(AndroidNativeDriverCommand.SET_SESSION_STORAGE_ITEM, post("/session/:sessionId/session_storage"))
//        .put(AndroidNativeDriverCommand.CLEAR_SESSION_STORAGE, delete("/session/:sessionId/session_storage"))
//        .put(AndroidNativeDriverCommand.GET_SESSION_STORAGE_SIZE, get("/session/:sessionId/session_storage/size"))
//
//        .put(AndroidNativeDriverCommand.GET_SCREEN_ORIENTATION, get("/session/:sessionId/orientation"))
//        .put(AndroidNativeDriverCommand.SET_SCREEN_ORIENTATION, post("/session/:sessionId/orientation"))
//
//            // Interactions-related commands.
//        .put(AndroidNativeDriverCommand.CLICK, post("/session/:sessionId/click"))
//        .put(AndroidNativeDriverCommand.DOUBLE_CLICK, post("/session/:sessionId/doubleclick"))
//        .put(AndroidNativeDriverCommand.MOUSE_DOWN, post("/session/:sessionId/buttondown"))
//        .put(AndroidNativeDriverCommand.MOUSE_UP, post("/session/:sessionId/buttonup"))
//        .put(AndroidNativeDriverCommand.MOVE_TO, post("/session/:sessionId/moveto"))
////        .put(AndroidNativeDriverCommand.SEND_KEYS_TO_ACTIVE_ELEMENT, post("/session/:sessionId/keys"))
//
//            // IME related commands.
//        .put(AndroidNativeDriverCommand.IME_GET_AVAILABLE_ENGINES, get("/session/:sessionId/ime/available_engines"))
//        .put(AndroidNativeDriverCommand.IME_GET_ACTIVE_ENGINE, get("/session/:sessionId/ime/active_engine"))
//        .put(AndroidNativeDriverCommand.IME_IS_ACTIVATED, get("/session/:sessionId/ime/activated"))
//        .put(AndroidNativeDriverCommand.IME_DEACTIVATE, post("/session/:sessionId/ime/deactivate"))
//        .put(AndroidNativeDriverCommand.IME_ACTIVATE_ENGINE, post("/session/:sessionId/ime/activate"))
//
//        .put(AndroidNativeDriverCommand.SET_TEXT_TO_ELEMENT, post("/session/:sessionId/element/:id/setText"))
//
//            // Advanced Touch API commands
//            // TODO(berrada): Refactor single tap with mouse click.
////        .put(TOUCH_SINGLE_TAP, post("/session/:sessionId/touch/click"))
////        .put(TOUCH_DOWN, post("/session/:sessionId/touch/down"))
////        .put(TOUCH_UP, post("/session/:sessionId/touch/up"))
////        .put(TOUCH_MOVE, post("/session/:sessionId/touch/move"))
////        .put(TOUCH_SCROLL, post("/session/:sessionId/touch/scroll"))
////        .put(TOUCH_DOUBLE_TAP, post("/session/:sessionId/touch/doubleclick"))
////        .put(TOUCH_LONG_PRESS, post("/session/:sessionId/touch/longclick"))
////        .put(TOUCH_FLICK, post("/session/:sessionId/touch/flick"))
////
////        .put(GET_LOGS, post("/session/:sessionId/log"))
////
////        .put(STATUS, get("/status"))
//
//        .build();
    
    
    this.nameToUrl = ImmutableMap.<String, CommandInfo>builder()
//    this.nameToUrl = ImmutableMap.builder()
        .put("newSession", post("/session"))
        .put("quit", delete("/session/:sessionId"))
        .put("getCurrentWindowHandle", get("/session/:sessionId/window_handle"))
        .put("getWindowHandles", get("/session/:sessionId/window_handles"))
        .put("get", post("/session/:sessionId/url"))
        .put("dismissAlert", post("/session/:sessionId/dismiss_alert"))
        .put("acceptAlert", post("/session/:sessionId/accept_alert"))
        .put("getAlertText", get("/session/:sessionId/alert_text"))
        .put("setAlertValue", post("/session/:sessionId/alert_text"))
        .put("goForward", post("/session/:sessionId/forward"))
        .put("goBack", post("/session/:sessionId/back"))
        .put("refresh", post("/session/:sessionId/refresh"))
        .put("executeScript", post("/session/:sessionId/execute"))
        .put("executeAsyncScript", post("/session/:sessionId/execute_async"))
        .put("getCurrentUrl", get("/session/:sessionId/url"))
        .put("getTitle", get("/session/:sessionId/title"))
        .put("getPageSource", get("/session/:sessionId/source"))
        .put("screenshot", get("/session/:sessionId/screenshot"))
        .put("setBrowserVisible", post("/session/:sessionId/visible"))
        .put("isBrowserVisible", get("/session/:sessionId/visible"))
        .put("findElement", post("/session/:sessionId/element"))
        .put("findElements", post("/session/:sessionId/elements"))
        .put("getActiveElement", post("/session/:sessionId/element/active"))
        .put("findChildElement", post("/session/:sessionId/element/:id/element"))
        .put("findChildElements", post("/session/:sessionId/element/:id/elements"))
        .put("clickElement", post("/session/:sessionId/element/:id/click"))
        .put("clearElement", post("/session/:sessionId/element/:id/clear"))
        .put("submitElement", post("/session/:sessionId/element/:id/submit"))
        .put("getElementText", get("/session/:sessionId/element/:id/text"))
        .put("sendKeysToElement", post("/session/:sessionId/element/:id/value"))
        .put("getElementValue", get("/session/:sessionId/element/:id/value"))
        .put("getElementTagName", get("/session/:sessionId/element/:id/name"))
        .put("isElementSelected", get("/session/:sessionId/element/:id/selected"))
        .put("setElementSelected", post("/session/:sessionId/element/:id/selected"))
        .put("toggleElement", post("/session/:sessionId/element/:id/toggle"))
        .put("isElementEnabled", get("/session/:sessionId/element/:id/enabled"))
        .put("isElementDisplayed", get("/session/:sessionId/element/:id/displayed"))
        .put("hoverOverElement", post("/session/:sessionId/element/:id/hover"))
        .put("getElementLocation", get("/session/:sessionId/element/:id/location"))
        .put("getElementLocationOnceScrolledIntoView", 
        get("/session/:sessionId/element/:id/location_in_view"))
        .put("getElementSize", get("/session/:sessionId/element/:id/size"))
        .put("getElementAttribute", get("/session/:sessionId/element/:id/attribute/:name"))
        .put("elementEquals", get("/session/:sessionId/element/:id/equals/:other"))
        .put("getCookies", get("/session/:sessionId/cookie"))
        .put("addCookie", post("/session/:sessionId/cookie"))
        .put("deleteAllCookies", delete("/session/:sessionId/cookie"))
        .put("deleteCookie", delete("/session/:sessionId/cookie/:name"))
        .put("switchToFrame", post("/session/:sessionId/frame"))
        .put("switchToWindow", post("/session/:sessionId/window"))
        .put("close", delete("/session/:sessionId/window"))
        .put("dragElement", post("/session/:sessionId/element/:id/drag"))
        .put("getElementValueOfCssProperty", 
        get("/session/:sessionId/element/:id/css/:propertyName"))
        .put("implicitlyWait", post("/session/:sessionId/timeouts/implicit_wait"))
        .put("setScriptTimeout", post("/session/:sessionId/timeouts/async_script"))
        .put("executeSQL", post("/session/:sessionId/execute_sql"))
        .put("getLocation", get("/session/:sessionId/location"))
        .put("setLocation", post("/session/:sessionId/location"))
        .put("getAppCache", get("/session/:sessionId/application_cache"))
        .put("getStatus", get("/session/:sessionId/application_cache/status"))
        .put("isBrowserOnline", get("/session/:sessionId/browser_connection"))
        .put("setBrowserOnline", post("/session/:sessionId/browser_connection"))
        .put("getLocalStorageItem", get("/session/:sessionId/local_storage/:key"))
        .put("removeLocalStorageItem", delete("/session/:sessionId/local_storage/:key"))
        .put("getLocalStorageKeys", get("/session/:sessionId/local_storage"))
        .put("setLocalStorageItem", post("/session/:sessionId/local_storage"))
        .put("clearLocalStorage", delete("/session/:sessionId/local_storage"))
        .put("getLocalStorageSize", get("/session/:sessionId/local_storage/size"))
        .put("getSessionStorageItem", get("/session/:sessionId/session_storage/:key"))
        .put("removeSessionStorageItem", delete("/session/:sessionId/session_storage/:key"))
        .put("getSessionStorageKey", get("/session/:sessionId/session_storage"))
        .put("setSessionStorageItem", post("/session/:sessionId/session_storage"))
        .put("clearSessionStorage", delete("/session/:sessionId/session_storage"))
        .put("getSessionStorageSize", get("/session/:sessionId/session_storage/size"))
        .put("getScreenOrientation", get("/session/:sessionId/orientation"))
        .put("setScreenOrientation", post("/session/:sessionId/orientation"))
        .put("mouseClick", post("/session/:sessionId/click"))
        .put("mouseDoubleClick", post("/session/:sessionId/doubleclick"))
        .put("mouseButtonDown", post("/session/:sessionId/buttondown"))
        .put("mouseButtonUp", post("/session/:sessionId/buttonup"))
        .put("mouseMoveTo", post("/session/:sessionId/moveto"))
        .put("sendModifierKeyToActiveElement", post("/session/:sessionId/modifier"))
        .put("imeGetAvailableEngines", get("/session/:sessionId/ime/available_engines"))
        .put("imeGetActiveEngine", get("/session/:sessionId/ime/active_engine"))
        .put("imeIsActivated", get("/session/:sessionId/ime/activated"))
        .put("imeDeactivate", post("/session/:sessionId/ime/deactivate"))
        .put("imeActivateEngine", post("/session/:sessionId/ime/activate"))
        .build();

  }

  public URL getAddressOfRemoteServer() {
    return remoteServer;
  }

  public Response execute(Command command) throws IOException {
    HttpContext context = new BasicHttpContext();

    CommandInfo info = nameToUrl.get(command.getName());
    try {
      HttpUriRequest httpMethod = info.getMethod(remoteServer, command);

      setAcceptHeader(httpMethod);

      if (httpMethod instanceof HttpPost) {
        String payload = new BeanToJsonConverter().convert(command.getParameters());
        ((HttpPost) httpMethod).setEntity(new StringEntity(payload, "utf-8"));
        httpMethod.addHeader("Content-Type", "application/json; charset=utf-8");
      }

      // Do not allow web proxy caches to cache responses to "get" commands
      if (httpMethod instanceof HttpGet)  {
        httpMethod.addHeader("Cache-Control", "no-cache");
      }

      HttpResponse response = fallBackExecute(context, httpMethod);

      response = followRedirects(client, context, response, /* redirect count */0);

      final EntityWithEncoding entityWithEncoding = new EntityWithEncoding(response.getEntity());

      return createResponse(response, context, entityWithEncoding);
    } catch (NullPointerException e) {
      // swallow an NPE on quit. It indicates that the sessionID is null
      // which is what we expect to be the case.
      if (DriverCommand.QUIT.equals(command.getName())) {
        return new Response();
      } else {
        throw e;
      }
    }
  }

  private HttpResponse fallBackExecute(HttpContext context, HttpUriRequest httpMethod)
      throws IOException {
    try {
      return client.execute(targetHost, httpMethod, context);
    } catch (BindException e) {
      // If we get this, there's a chance we've used all the local ephemeral sockets
      // Sleep for a bit to let the OS reclaim them, then try the request again.
      try {
        Thread.sleep(2000);
      } catch (InterruptedException ie) {
        throw Throwables.propagate(ie);
      }
    } catch (NoHttpResponseException e) {
      // If we get this, there's a chance we've used all the remote ephemeral sockets
      // Sleep for a bit to let the OS reclaim them, then try the request again.
      try {
        Thread.sleep(2000);
      } catch (InterruptedException ie) {
        throw Throwables.propagate(ie);
      }
    }
    return client.execute(targetHost, httpMethod, context);
  }

  private void setAcceptHeader(HttpUriRequest httpMethod) {
    httpMethod.addHeader("Accept", "application/json, image/png");
  }

  private HttpResponse followRedirects(
      HttpClient client, HttpContext context, HttpResponse response, int redirectCount) {
    if (!isRedirect(response)) {
      return response;
    }

    try {
      // Make sure that the previous connection is freed.
      HttpEntity httpEntity = response.getEntity();
      if (httpEntity != null) {
        httpEntity.consumeContent();
      }
    } catch (IOException e) {
      throw new WebDriverException(e);
    }

    if (redirectCount > MAX_REDIRECTS) {
      throw new WebDriverException("Maximum number of redirects exceeded. Aborting");
    }

    String location = response.getFirstHeader("location").getValue();
    URI uri;
    try {
      uri = buildUri(context, location);

      HttpGet get = new HttpGet(uri);
      setAcceptHeader(get);
      HttpResponse newResponse = client.execute(targetHost, get, context);
      return followRedirects(client, context, newResponse, redirectCount + 1);
    } catch (URISyntaxException e) {
      throw new WebDriverException(e);
    } catch (ClientProtocolException e) {
      throw new WebDriverException(e);
    } catch (IOException e) {
      throw new WebDriverException(e);
    }
  }

  private URI buildUri(HttpContext context, String location) throws URISyntaxException {
    URI uri;
    uri = new URI(location);
    if (!uri.isAbsolute()) {
      HttpHost host = (HttpHost) context.getAttribute(HTTP_TARGET_HOST);
      uri = new URI(host.toURI() + location);
    }
    return uri;
  }

  private boolean isRedirect(HttpResponse response) {
    int code = response.getStatusLine().getStatusCode();

    return (code == 301 || code == 302 || code == 303 || code == 307)
           && response.containsHeader("location");
  }

  class EntityWithEncoding {

    private final String charSet;
    private final byte[] content;

    EntityWithEncoding(HttpEntity entity) throws IOException {
      try {
        if (entity != null) {
          content = EntityUtils.toByteArray(entity);
          charSet = EntityUtils.getContentCharSet(entity);
        } else {
          content = new byte[0];
          charSet = null;
        }
      } finally {
//        EntityUtils.consume(entity);
        entity.consumeContent();
      }
    }

    public String getContentString()
        throws UnsupportedEncodingException {
      return new String(content, charSet != null ? charSet : "utf-8");
    }

    public byte[] getContent() {
      return content;
    }

    public boolean hasEntityContent() {
      return content != null;
    }
  }


  private Response createResponse(HttpResponse httpResponse, HttpContext context,
                                  EntityWithEncoding entityWithEncoding) throws IOException {
    final Response response;

    Header header = httpResponse.getFirstHeader("Content-Type");

    if (header != null && header.getValue().startsWith("application/json")) {
      String responseAsText = entityWithEncoding.getContentString();

      try {
        response = new JsonToBeanConverter().convert(Response.class, responseAsText);
      } catch (ClassCastException e) {
        if (responseAsText != null && "".equals(responseAsText)) {
          // The remote server has died, but has already set some headers.
          // Normally this occurs when the final window of the firefox driver
          // is closed on OS X. Return null, as the return value _should_ be
          // being ignored. This is not an elegant solution.
          return null;
        }
        throw new WebDriverException("Cannot convert text to response: " + responseAsText, e);
      }
    } else {
      response = new Response();

      if (header != null && header.getValue().startsWith("image/png")) {
        response.setValue(entityWithEncoding.getContent());
      } else if (entityWithEncoding.hasEntityContent()) {
        response.setValue(entityWithEncoding.getContentString());
      }

      HttpHost finalHost = (HttpHost) context.getAttribute(HTTP_TARGET_HOST);
      String uri = finalHost.toURI();
      String sessionId = getSessionId(uri);
      if (sessionId != null) {
        response.setSessionId(sessionId);
      }

      int statusCode = httpResponse.getStatusLine().getStatusCode();
      if (!(statusCode > 199 && statusCode < 300)) {
        // 4xx represents an unknown command or a bad request.
        if (statusCode > 399 && statusCode < 500) {
          response.setStatus(ErrorCodes.UNKNOWN_COMMAND);
        } else if (statusCode > 499 && statusCode < 600) {
          // 5xx represents an internal server error. The response status should already be set, but
          // if not, set it to a general error code.
          if (response.getStatus() == ErrorCodes.SUCCESS) {
            response.setStatus(ErrorCodes.UNHANDLED_ERROR);
          }
        } else {
          response.setStatus(ErrorCodes.UNHANDLED_ERROR);
        }
      }

      if (response.getValue() instanceof String) {
        // We normalise to \n because Java will translate this to \r\n
        // if this is suitable on our platform, and if we have \r\n, java will
        // turn this into \r\r\n, which would be Bad!
        response.setValue(((String) response.getValue()).replace("\r\n", "\n"));
      }
    }
    return response;
  }

  public static String getSessionId(String uri) {
    int sessionIndex = uri.indexOf("/session/");
    if (sessionIndex != -1) {
      sessionIndex += "/session/".length();
      int nextSlash = uri.indexOf("/", sessionIndex);
      if (nextSlash != -1) {
        return uri.substring(sessionIndex, nextSlash);
      } else {
        return uri.substring(sessionIndex);
      }

    }
    return null;
  }

  private static CommandInfo get(String url) {
    return new CommandInfo(url, HttpVerb.GET);
  }

  private static CommandInfo post(String url) {
    return new CommandInfo(url, HttpVerb.POST);
  }

  private static CommandInfo delete(String url) {
    return new CommandInfo(url, HttpVerb.DELETE);
  }

  private static class CommandInfo {

    private final String url;
    private final HttpVerb verb;

    private CommandInfo(String url, HttpVerb verb) {
      this.url = url;
      this.verb = verb;
    }

    public HttpUriRequest getMethod(URL base, Command command) {
      StringBuilder urlBuilder = new StringBuilder();

      urlBuilder.append(base.toExternalForm().replaceAll("/$", ""));
      for (String part : url.split("/")) {
        if (part.length() == 0) {
          continue;
        }

        urlBuilder.append("/");
        if (part.startsWith(":")) {
          String value = get(part.substring(1), command);
          if (value != null) {
            urlBuilder.append(get(part.substring(1), command));
          }
        } else {
          urlBuilder.append(part);
        }
      }

      return verb.createMethod(urlBuilder.toString());
    }

    private String get(String propertyName, Command command) {
      if ("sessionId".equals(propertyName)) {
        SessionId id = command.getSessionId();
        if (id == null) {
          throw new WebDriverException("Session ID may not be null");
        }
        return id.toString();
      }

      // Attempt to extract the property name from the parameters
      Object value = command.getParameters().get(propertyName);
      if (value != null) {
        return Urls.urlEncode(String.valueOf(value));
      }
      return null;
    }
  }
}