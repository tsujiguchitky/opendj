/*
 * CDDL HEADER START
 *
 * The contents of this file are subject to the terms of the
 * Common Development and Distribution License, Version 1.0 only
 * (the "License").  You may not use this file except in compliance
 * with the License.
 *
 * You can obtain a copy of the license at legal-notices/CDDLv1_0.txt
 * or http://forgerock.org/license/CDDLv1.0.html.
 * See the License for the specific language governing permissions
 * and limitations under the License.
 *
 * When distributing Covered Code, include this CDDL HEADER in each
 * file and include the License file at legal-notices/CDDLv1_0.txt.
 * If applicable, add the following below this CDDL HEADER, with the
 * fields enclosed by brackets "[]" replaced with your own identifying
 * information:
 *      Portions Copyright [yyyy] [name of copyright owner]
 *
 * CDDL HEADER END
 *
 *
 *      Copyright 2013-2015 ForgeRock AS
 */
package org.opends.server.protocols.http;

import static org.opends.messages.ConfigMessages.WARN_CONFIG_LOGGER_NO_ACTIVE_HTTP_ACCESS_LOGGERS;
import static org.opends.messages.ProtocolMessages.*;
import static org.opends.server.util.ServerConstants.ALERT_DESCRIPTION_HTTP_CONNECTION_HANDLER_CONSECUTIVE_FAILURES;
import static org.opends.server.util.ServerConstants.ALERT_TYPE_HTTP_CONNECTION_HANDLER_CONSECUTIVE_FAILURES;
import static org.opends.server.util.StaticUtils.getExceptionMessage;
import static org.opends.server.util.StaticUtils.isAddressInUse;
import static org.opends.server.util.StaticUtils.stackTraceToSingleLineString;

import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;

import java.io.IOException;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.forgerock.http.servlet.HttpFrameworkServlet;
import org.forgerock.i18n.LocalizableMessage;
import org.forgerock.i18n.slf4j.LocalizedLogger;
import org.forgerock.opendj.config.server.ConfigChangeResult;
import org.forgerock.opendj.config.server.ConfigException;
import org.forgerock.opendj.ldap.ResultCode;
import org.glassfish.grizzly.http.HttpProbe;
import org.glassfish.grizzly.http.server.HttpServer;
import org.glassfish.grizzly.http.server.NetworkListener;
import org.glassfish.grizzly.http.server.ServerConfiguration;
import org.glassfish.grizzly.monitoring.MonitoringConfig;
import org.glassfish.grizzly.nio.transport.TCPNIOTransport;
import org.glassfish.grizzly.servlet.WebappContext;
import org.glassfish.grizzly.ssl.SSLEngineConfigurator;
import org.glassfish.grizzly.strategies.SameThreadIOStrategy;
import org.glassfish.grizzly.utils.Charsets;
import org.opends.server.admin.server.ConfigurationChangeListener;
import org.opends.server.admin.std.server.ConnectionHandlerCfg;
import org.opends.server.admin.std.server.HTTPConnectionHandlerCfg;
import org.opends.server.api.AlertGenerator;
import org.opends.server.api.ClientConnection;
import org.opends.server.api.ConnectionHandler;
import org.opends.server.api.KeyManagerProvider;
import org.opends.server.api.ServerShutdownListener;
import org.opends.server.api.TrustManagerProvider;
import org.opends.server.core.DirectoryServer;
import org.opends.server.core.ServerContext;
import org.opends.server.extensions.NullKeyManagerProvider;
import org.opends.server.extensions.NullTrustManagerProvider;
import org.opends.server.loggers.HTTPAccessLogger;
import org.opends.server.monitors.ClientConnectionMonitorProvider;
import org.opends.server.types.DN;
import org.opends.server.types.DirectoryException;
import org.opends.server.types.HostPort;
import org.opends.server.types.InitializationException;
import org.opends.server.util.SelectableCertificateKeyManager;
import org.opends.server.util.StaticUtils;

/**
 * This class defines a connection handler that will be used for communicating
 * with clients over HTTP. The connection handler is responsible for
 * starting/stopping the embedded web server.
 */
public class HTTPConnectionHandler extends ConnectionHandler<HTTPConnectionHandlerCfg>
                                   implements ConfigurationChangeListener<HTTPConnectionHandlerCfg>,
                                              ServerShutdownListener,
                                              AlertGenerator
{
  /** The tracer object for the debug logger. */
  private static final LocalizedLogger logger = LocalizedLogger.getLoggerForThisClass();

  /** Default friendly name for this connection handler. */
  private static final String DEFAULT_FRIENDLY_NAME = "HTTP Connection Handler";

  /** SSL instance name used in context creation. */
  private static final String SSL_CONTEXT_INSTANCE_NAME = "TLS";

  /** The initialization configuration. */
  private HTTPConnectionHandlerCfg initConfig;

  /** The current configuration. */
  private HTTPConnectionHandlerCfg currentConfig;

  /** Indicates whether the Directory Server is in the process of shutting down. */
  private volatile boolean shutdownRequested;

  /** Indicates whether this connection handler is enabled. */
  private boolean enabled;

  /** The set of listeners for this connection handler. */
  private List<HostPort> listeners = new LinkedList<>();

  /** The HTTP server embedded in OpenDJ. */
  private HttpServer httpServer;

  /** The HTTP probe that collects stats. */
  private HTTPStatsProbe httpProbe;

  /**
   * Holds the current client connections. Using {@link ConcurrentHashMap} to
   * ensure no concurrent reads/writes can happen and adds/removes are fast. We
   * only use the keys, so it does not matter what value is put there.
   */
  private Map<ClientConnection, ClientConnection> clientConnections = new ConcurrentHashMap<>();

  /** The set of statistics collected for this connection handler. */
  private HTTPStatistics statTracker;

  /** The client connection monitor provider associated with this connection handler. */
  private ClientConnectionMonitorProvider connMonitor;

  /** The unique name assigned to this connection handler. */
  private String handlerName;

  /** The protocol used by this connection handler. */
  private String protocol;

  /**
   * The condition variable that will be used by the start method to wait for
   * the socket port to be opened and ready to process requests before returning.
   */
  private final Object waitListen = new Object();

  /** The friendly name of this connection handler. */
  private String friendlyName;

  /** The SSL engine configurator is used for obtaining default SSL parameters. */
  private SSLEngineConfigurator sslEngineConfigurator;

  private ServerContext serverContext;

  /** Default constructor. It is invoked by reflection to create this {@link ConnectionHandler}. */
  public HTTPConnectionHandler()
  {
    super(DEFAULT_FRIENDLY_NAME);
  }

  /**
   * Returns whether unauthenticated HTTP requests are allowed. The server
   * checks whether unauthenticated requests are allowed server-wide first then
   * for the HTTP Connection Handler second.
   *
   * @return true if unauthenticated requests are allowed, false otherwise.
   */
  public boolean acceptUnauthenticatedRequests()
  {
    // The global setting overrides the more specific setting here.
    return !DirectoryServer.rejectUnauthenticatedRequests() && !this.currentConfig.isAuthenticationRequired();
  }

  /**
   * Registers a client connection to track it.
   *
   * @param clientConnection
   *          the client connection to register
   */
  void addClientConnection(ClientConnection clientConnection)
  {
    clientConnections.put(clientConnection, clientConnection);
  }

  @Override
  public ConfigChangeResult applyConfigurationChange(HTTPConnectionHandlerCfg config)
  {
    final ConfigChangeResult ccr = new ConfigChangeResult();

    if (anyChangeRequiresRestart(config))
    {
      ccr.setAdminActionRequired(true);
      ccr.addMessage(ERR_CONNHANDLER_CONFIG_CHANGES_REQUIRE_RESTART.get("HTTP"));
    }

    // Reconfigure SSL if needed.
    try
    {
      configureSSL(config);
    }
    catch (DirectoryException e)
    {
      logger.traceException(e);
      ccr.setResultCode(e.getResultCode());
      ccr.addMessage(e.getMessageObject());
      return ccr;
    }

    if (config.isEnabled() && this.currentConfig.isEnabled() && isListening())
    {
      // Server was running and will still be running if the "enabled" was flipped,
      // leave it to the stop / start server to handle it.
      if (!this.currentConfig.isKeepStats() && config.isKeepStats())
      {
        // It must now keep stats while it was not previously.
        setHttpStatsProbe(this.httpServer);
      }
      else if (this.currentConfig.isKeepStats() && !config.isKeepStats() && this.httpProbe != null)
      {
        // It must NOT keep stats anymore.
        getHttpConfig(this.httpServer).removeProbes(this.httpProbe);
        this.httpProbe = null;
      }
    }

    this.initConfig = config;
    this.currentConfig = config;
    this.enabled = this.currentConfig.isEnabled();

    return ccr;
  }

  private boolean anyChangeRequiresRestart(HTTPConnectionHandlerCfg newCfg)
  {
    return !equals(newCfg.getListenPort(), initConfig.getListenPort())
        || !Objects.equals(newCfg.getListenAddress(), initConfig.getListenAddress())
        || !equals(newCfg.getMaxRequestSize(), currentConfig.getMaxRequestSize())
        || !equals(newCfg.isAllowTCPReuseAddress(), currentConfig.isAllowTCPReuseAddress())
        || !equals(newCfg.isUseTCPKeepAlive(), currentConfig.isUseTCPKeepAlive())
        || !equals(newCfg.isUseTCPNoDelay(), currentConfig.isUseTCPNoDelay())
        || !equals(newCfg.getMaxBlockedWriteTimeLimit(), currentConfig.getMaxBlockedWriteTimeLimit())
        || !equals(newCfg.getBufferSize(), currentConfig.getBufferSize())
        || !equals(newCfg.getAcceptBacklog(), currentConfig.getAcceptBacklog())
        || !equals(newCfg.isUseSSL(), currentConfig.isUseSSL())
        || !Objects.equals(newCfg.getKeyManagerProviderDN(), currentConfig.getKeyManagerProviderDN())
        || !Objects.equals(newCfg.getSSLCertNickname(), currentConfig.getSSLCertNickname())
        || !Objects.equals(newCfg.getTrustManagerProviderDN(), currentConfig.getTrustManagerProviderDN())
        || !Objects.equals(newCfg.getSSLProtocol(), currentConfig.getSSLProtocol())
        || !Objects.equals(newCfg.getSSLCipherSuite(), currentConfig.getSSLCipherSuite())
        || !Objects.equals(newCfg.getSSLClientAuthPolicy(), currentConfig.getSSLClientAuthPolicy());
  }

  private boolean equals(long l1, long l2)
  {
    return l1 == l2;
  }

  private boolean equals(boolean b1, boolean b2)
  {
    return b1 == b2;
  }

  private void configureSSL(HTTPConnectionHandlerCfg config)
      throws DirectoryException
  {
    protocol = config.isUseSSL() ? "HTTPS" : "HTTP";
    if (config.isUseSSL())
    {
      sslEngineConfigurator = createSSLEngineConfigurator(config);
    }
    else
    {
      sslEngineConfigurator = null;
    }
  }

  @Override
  public void finalizeConnectionHandler(LocalizableMessage finalizeReason)
  {
    shutdownRequested = true;
    // Unregister this as a change listener.
    currentConfig.removeHTTPChangeListener(this);

    if (connMonitor != null)
    {
      DirectoryServer.deregisterMonitorProvider(connMonitor);
    }

    if (statTracker != null)
    {
      DirectoryServer.deregisterMonitorProvider(statTracker);
    }
  }

  @Override
  public Map<String, String> getAlerts()
  {
    Map<String, String> alerts = new LinkedHashMap<>();

    alerts.put(ALERT_TYPE_HTTP_CONNECTION_HANDLER_CONSECUTIVE_FAILURES,
               ALERT_DESCRIPTION_HTTP_CONNECTION_HANDLER_CONSECUTIVE_FAILURES);

    return alerts;
  }

  @Override
  public String getClassName()
  {
    return HTTPConnectionHandler.class.getName();
  }

  @Override
  public Collection<ClientConnection> getClientConnections()
  {
    return clientConnections.keySet();
  }

  @Override
  public DN getComponentEntryDN()
  {
    return currentConfig.dn();
  }

  @Override
  public String getConnectionHandlerName()
  {
    return handlerName;
  }

  /**
   * Returns the current config of this connection handler.
   *
   * @return the current config of this connection handler
   */
  HTTPConnectionHandlerCfg getCurrentConfig()
  {
    return this.currentConfig;
  }

  @Override
  public Collection<String> getEnabledSSLCipherSuites()
  {
    final SSLEngineConfigurator configurator = sslEngineConfigurator;
    if (configurator != null)
    {
      return Arrays.asList(configurator.getEnabledCipherSuites());
    }
    return super.getEnabledSSLCipherSuites();
  }

  @Override
  public Collection<String> getEnabledSSLProtocols()
  {
    final SSLEngineConfigurator configurator = sslEngineConfigurator;
    if (configurator != null)
    {
      return Arrays.asList(configurator.getEnabledProtocols());
    }
    return super.getEnabledSSLProtocols();
  }

  @Override
  public Collection<HostPort> getListeners()
  {
    return listeners;
  }

  /**
   * Returns the listen port for this connection handler.
   *
   * @return the listen port for this connection handler.
   */
  int getListenPort()
  {
    return this.initConfig.getListenPort();
  }

  @Override
  public String getProtocol()
  {
    return protocol;
  }

  /**
   * Returns the SSL engine configured for this connection handler if SSL is
   * enabled, null otherwise.
   *
   * @return the SSL engine if SSL is enabled, null otherwise
   */
  SSLEngine getSSLEngine()
  {
    return sslEngineConfigurator.createSSLEngine();
  }

  @Override
  public String getShutdownListenerName()
  {
    return handlerName;
  }

  /**
   * Retrieves the set of statistics maintained by this connection handler.
   *
   * @return The set of statistics maintained by this connection handler.
   */
  public HTTPStatistics getStatTracker()
  {
    return statTracker;
  }

  @Override
  public void initializeConnectionHandler(ServerContext serverContext, HTTPConnectionHandlerCfg config)
      throws ConfigException, InitializationException
  {
    this.serverContext = serverContext;
    this.enabled = config.isEnabled();

    if (friendlyName == null)
    {
      friendlyName = config.dn().rdn().getAttributeValue(0).toString();
    }

    int listenPort = config.getListenPort();
    for (InetAddress a : config.getListenAddress())
    {
      listeners.add(new HostPort(a.getHostAddress(), listenPort));
    }

    handlerName = getHandlerName(config);

    // Configure SSL if needed.
    try
    {
      // This call may disable the connector if wrong SSL settings
      configureSSL(config);
    }
    catch (DirectoryException e)
    {
      logger.traceException(e);
      throw new InitializationException(e.getMessageObject());
    }

    // Create and register monitors.
    statTracker = new HTTPStatistics(handlerName + " Statistics");
    DirectoryServer.registerMonitorProvider(statTracker);

    connMonitor = new ClientConnectionMonitorProvider(this);
    DirectoryServer.registerMonitorProvider(connMonitor);

    // Register this as a change listener.
    config.addHTTPChangeListener(this);

    this.initConfig = config;
    this.currentConfig = config;
  }

  private String getHandlerName(HTTPConnectionHandlerCfg config)
  {
    StringBuilder nameBuffer = new StringBuilder();
    nameBuffer.append(friendlyName);
    for (InetAddress a : config.getListenAddress())
    {
      nameBuffer.append(" ");
      nameBuffer.append(a.getHostAddress());
    }
    nameBuffer.append(" port ");
    nameBuffer.append(config.getListenPort());
    return nameBuffer.toString();
  }

  @Override
  public boolean isConfigurationAcceptable(
      ConnectionHandlerCfg configuration, List<LocalizableMessage> unacceptableReasons)
  {
    HTTPConnectionHandlerCfg config = (HTTPConnectionHandlerCfg) configuration;

    if (currentConfig == null || (!this.enabled && config.isEnabled()))
    {
      // Attempt to bind to the listen port on all configured addresses to
      // verify whether the connection handler will be able to start.
      LocalizableMessage errorMessage = checkAnyListenAddressInUse(
          config.getListenAddress(), config.getListenPort(), config.isAllowTCPReuseAddress(), config.dn());
      if (errorMessage != null)
      {
        unacceptableReasons.add(errorMessage);
        return false;
      }
    }

    if (config.isEnabled() && config.isUseSSL())
    {
      try
      {
        createSSLEngineConfigurator(config);
      }
      catch (DirectoryException e)
      {
        logger.traceException(e);
        unacceptableReasons.add(e.getMessageObject());
        return false;
      }
    }

    return true;
  }

  /**
   * Checks whether any listen address is in use for the given port. The check
   * is performed by binding to each address and port.
   *
   * @param listenAddresses
   *          the listen {@link InetAddress} to test
   * @param listenPort
   *          the listen port to test
   * @param allowReuseAddress
   *          whether addresses can be reused
   * @param configEntryDN
   *          the configuration entry DN
   * @return an error message if at least one of the address is already in use,
   *         null otherwise.
   */
  private LocalizableMessage checkAnyListenAddressInUse(
      Collection<InetAddress> listenAddresses, int listenPort, boolean allowReuseAddress, DN configEntryDN)
  {
    for (InetAddress a : listenAddresses)
    {
      try
      {
        if (isAddressInUse(a, listenPort, allowReuseAddress))
        {
          throw new IOException(ERR_CONNHANDLER_ADDRESS_INUSE.get().toString());
        }
      }
      catch (IOException e)
      {
        logger.traceException(e);
        return ERR_CONNHANDLER_CANNOT_BIND.get(
            "HTTP", configEntryDN, a.getHostAddress(), listenPort, getExceptionMessage(e));
      }
    }
    return null;
  }

  @Override
  public boolean isConfigurationChangeAcceptable(
      HTTPConnectionHandlerCfg configuration, List<LocalizableMessage> unacceptableReasons)
  {
    return isConfigurationAcceptable(configuration, unacceptableReasons);
  }

  /**
   * Indicates whether this connection handler should maintain usage statistics.
   *
   * @return <CODE>true</CODE> if this connection handler should maintain usage
   *         statistics, or <CODE>false</CODE> if not.
   */
  public boolean keepStats()
  {
    return currentConfig.isKeepStats();
  }

  @Override
  public void processServerShutdown(LocalizableMessage reason)
  {
    shutdownRequested = true;
  }

  private boolean isListening()
  {
    return httpServer != null;
  }

  @Override
  public void start()
  {
    // The Directory Server start process should only return when the connection handlers port
    // are fully opened and working.
    // The start method therefore needs to wait for the created thread too.
    synchronized (waitListen)
    {
      super.start();

      try
      {
        waitListen.wait();
      }
      catch (InterruptedException e)
      {
        // If something interrupted the start its probably better to return ASAP
      }
    }
  }

  /**
   * Unregisters a client connection to stop tracking it.
   *
   * @param clientConnection
   *          the client connection to unregister
   */
  void removeClientConnection(ClientConnection clientConnection)
  {
    clientConnections.remove(clientConnection);
  }

  @Override
  public void run()
  {
    setName(handlerName);

    boolean lastIterationFailed = false;
    boolean starting = true;

    while (!shutdownRequested)
    {
      // If this connection handler is not enabled, then just sleep for a bit and check again.
      if (!this.enabled)
      {
        if (isListening())
        {
          stopHttpServer();
        }

        if (starting)
        {
          // This may happen if there was an initialisation error which led to disable the connector.
          // The main thread is waiting for the connector to listen on its port, which will not occur yet,
          // so notify here to allow the server startup to complete.
          synchronized (waitListen)
          {
            starting = false;
            waitListen.notify();
          }
        }

        StaticUtils.sleep(1000);
        continue;
      }

      if (isListening())
      {
        // If already listening, then sleep for a bit and check again.
        StaticUtils.sleep(1000);
        continue;
      }

      try
      {
        // At this point, the connection Handler either started correctly or failed
        // to start but the start process should be notified and resume its work in any cases.
        synchronized (waitListen)
        {
          waitListen.notify();
        }

        // If we have gotten here, then we are about to start listening
        // for the first time since startup or since we were previously disabled.
        // Start the embedded HTTP server
        startHttpServer();
        lastIterationFailed = false;
      }
      catch (Exception e)
      {
        // Clean up the messed up HTTP server
        cleanUpHttpServer();

        // Error + alert about the horked config
        logger.traceException(e);
        logger.error(
            ERR_CONNHANDLER_CANNOT_ACCEPT_CONNECTION, friendlyName, currentConfig.dn(), getExceptionMessage(e));

        if (lastIterationFailed)
        {
          // The last time through the accept loop we also encountered a failure.
          // Rather than enter a potential infinite loop of failures,
          // disable this acceptor and log an error.
          LocalizableMessage message = ERR_CONNHANDLER_CONSECUTIVE_ACCEPT_FAILURES.get(
              friendlyName, currentConfig.dn(), stackTraceToSingleLineString(e));
          logger.error(message);

          DirectoryServer.sendAlertNotification(this, ALERT_TYPE_HTTP_CONNECTION_HANDLER_CONSECUTIVE_FAILURES, message);
          this.enabled = false;
        }
        else
        {
          lastIterationFailed = true;
        }
      }
    }

    // Initiate shutdown
    stopHttpServer();
  }

  private void startHttpServer() throws Exception
  {
    // Silence Grizzly's own logging
    Logger.getLogger("org.glassfish.grizzly").setLevel(Level.OFF);

    if (HTTPAccessLogger.getHTTPAccessLogPublishers().isEmpty())
    {
      logger.warn(WARN_CONFIG_LOGGER_NO_ACTIVE_HTTP_ACCESS_LOGGERS);
    }

    this.httpServer = createHttpServer();

    // Register servlet as default servlet and also able to serve REST requests
    createAndRegisterServlet("OpenDJ Rest2LDAP servlet", "", "/*");

    logger.trace("Starting HTTP server...");
    this.httpServer.start();
    logger.trace("HTTP server started");
    logger.info(NOTE_CONNHANDLER_STARTED_LISTENING, handlerName);
  }

  private HttpServer createHttpServer()
  {
    final HttpServer server = new HttpServer();

    final int requestSize = (int) currentConfig.getMaxRequestSize();
    final ServerConfiguration serverConfig = server.getServerConfiguration();
    serverConfig.setMaxBufferedPostSize(requestSize);
    serverConfig.setMaxFormPostSize(requestSize);
    serverConfig.setDefaultQueryEncoding(Charsets.UTF8_CHARSET);

    if (keepStats())
    {
      setHttpStatsProbe(server);
    }

    // Configure the network listener
    final NetworkListener listener = new NetworkListener(
        "Rest2LDAP", NetworkListener.DEFAULT_NETWORK_HOST, initConfig.getListenPort());
    server.addListener(listener);

    // Configure the network transport
    final TCPNIOTransport transport = listener.getTransport();
    transport.setReuseAddress(currentConfig.isAllowTCPReuseAddress());
    transport.setKeepAlive(currentConfig.isUseTCPKeepAlive());
    transport.setTcpNoDelay(currentConfig.isUseTCPNoDelay());
    transport.setWriteTimeout(currentConfig.getMaxBlockedWriteTimeLimit(), TimeUnit.MILLISECONDS);

    final int bufferSize = (int) currentConfig.getBufferSize();
    transport.setReadBufferSize(bufferSize);
    transport.setWriteBufferSize(bufferSize);
    transport.setIOStrategy(SameThreadIOStrategy.getInstance());

    final int numRequestHandlers = getNumRequestHandlers(currentConfig.getNumRequestHandlers(), friendlyName);
    transport.setSelectorRunnersCount(numRequestHandlers);
    transport.setServerConnectionBackLog(currentConfig.getAcceptBacklog());

    // Configure SSL
    if (sslEngineConfigurator != null)
    {
      listener.setSecure(true);
      listener.setSSLEngineConfig(sslEngineConfigurator);
    }

    return server;
  }

  private void setHttpStatsProbe(HttpServer server)
  {
    this.httpProbe = new HTTPStatsProbe(this.statTracker);
    getHttpConfig(server).addProbes(this.httpProbe);
  }

  private MonitoringConfig<HttpProbe> getHttpConfig(HttpServer server)
  {
    return server.getServerConfiguration().getMonitoringConfig().getHttpConfig();
  }

  private void createAndRegisterServlet(final String servletName, final String... urlPatterns) throws Exception
  {
    // Create and deploy the Web app context
    final WebappContext ctx = new WebappContext(servletName);
    ctx.addServlet(servletName,
        new HttpFrameworkServlet(new LdapHttpApplication(serverContext, this))).addMapping(urlPatterns);
    ctx.deploy(this.httpServer);
  }

  private void stopHttpServer()
  {
    if (this.httpServer != null)
    {
      logger.trace("Stopping HTTP server...");
      this.httpServer.shutdownNow();
      cleanUpHttpServer();
      logger.trace("HTTP server stopped");
      logger.info(NOTE_CONNHANDLER_STOPPED_LISTENING, handlerName);
    }
  }

  private void cleanUpHttpServer()
  {
    this.httpServer = null;
    this.httpProbe = null;
  }

  @Override
  public void toString(StringBuilder buffer)
  {
    buffer.append(handlerName);
  }

  private SSLEngineConfigurator createSSLEngineConfigurator(HTTPConnectionHandlerCfg config) throws DirectoryException
  {
    if (!config.isUseSSL())
    {
      return null;
    }

    try
    {
      SSLContext sslContext = createSSLContext(config);
      SSLEngineConfigurator configurator = new SSLEngineConfigurator(sslContext);
      configurator.setClientMode(false);

      // configure with defaults from the JVM
      final SSLEngine defaults = sslContext.createSSLEngine();
      configurator.setEnabledProtocols(defaults.getEnabledProtocols());
      configurator.setEnabledCipherSuites(defaults.getEnabledCipherSuites());

      final Set<String> protocols = config.getSSLProtocol();
      if (!protocols.isEmpty())
      {
        configurator.setEnabledProtocols(protocols.toArray(new String[protocols.size()]));
      }

      final Set<String> ciphers = config.getSSLCipherSuite();
      if (!ciphers.isEmpty())
      {
        configurator.setEnabledCipherSuites(ciphers.toArray(new String[ciphers.size()]));
      }

      switch (config.getSSLClientAuthPolicy())
      {
      case DISABLED:
        configurator.setNeedClientAuth(false);
        configurator.setWantClientAuth(false);
        break;
      case REQUIRED:
        configurator.setNeedClientAuth(true);
        configurator.setWantClientAuth(true);
        break;
      case OPTIONAL:
      default:
        configurator.setNeedClientAuth(false);
        configurator.setWantClientAuth(true);
        break;
      }

      return configurator;
    }
    catch (Exception e)
    {
      logger.traceException(e);
      ResultCode resCode = DirectoryServer.getServerErrorResultCode();
      throw new DirectoryException(resCode, ERR_CONNHANDLER_SSL_CANNOT_INITIALIZE.get(getExceptionMessage(e)), e);
    }
  }

  private SSLContext createSSLContext(HTTPConnectionHandlerCfg config) throws Exception
  {
    if (!config.isUseSSL())
    {
      return null;
    }

    DN keyMgrDN = config.getKeyManagerProviderDN();
    KeyManagerProvider<?> keyManagerProvider = DirectoryServer.getKeyManagerProvider(keyMgrDN);
    if (keyManagerProvider == null)
    {
      logger.error(ERR_NULL_KEY_PROVIDER_MANAGER, keyMgrDN, friendlyName);
      logger.warn(INFO_DISABLE_CONNECTION, friendlyName);
      keyManagerProvider = new NullKeyManagerProvider();
      enabled = false;
    }
    else if (!keyManagerProvider.containsAtLeastOneKey())
    {
      logger.error(ERR_INVALID_KEYSTORE, friendlyName);
      logger.warn(INFO_DISABLE_CONNECTION, friendlyName);
      enabled = false;
    }

    final SortedSet<String> aliases = new TreeSet<>(config.getSSLCertNickname());
    final KeyManager[] keyManagers;
    if (aliases.isEmpty())
    {
      keyManagers = keyManagerProvider.getKeyManagers();
    }
    else
    {
      final Iterator<String> it = aliases.iterator();
      while (it.hasNext())
      {
        if (!keyManagerProvider.containsKeyWithAlias(it.next()))
        {
          logger.error(ERR_KEYSTORE_DOES_NOT_CONTAIN_ALIAS, aliases, friendlyName);
          it.remove();
        }
      }
      if (aliases.isEmpty())
      {
        logger.warn(INFO_DISABLE_CONNECTION, friendlyName);
        enabled = false;
      }
      keyManagers = SelectableCertificateKeyManager.wrap(keyManagerProvider.getKeyManagers(), aliases);
    }

    DN trustMgrDN = config.getTrustManagerProviderDN();
    TrustManagerProvider<?> trustManagerProvider = DirectoryServer.getTrustManagerProvider(trustMgrDN);
    if (trustManagerProvider == null)
    {
      trustManagerProvider = new NullTrustManagerProvider();
    }

    SSLContext sslContext = SSLContext.getInstance(SSL_CONTEXT_INSTANCE_NAME);
    sslContext.init(keyManagers, trustManagerProvider.getTrustManagers(), null);
    return sslContext;
  }
}
