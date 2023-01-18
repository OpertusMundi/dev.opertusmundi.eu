//usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS io.quarkus.platform:quarkus-bom:2.7.5.Final@pom
//DEPS io.quarkus:quarkus-picocli
//DEPS io.quarkus:quarkus-kubernetes-client
//DEPS io.quarkus:quarkus-agroal
//DEPS io.quarkus:quarkus-jdbc-postgresql
//DEPS org.projectlombok:lombok:1.18.22
//DEPS net.ttddyy:datasource-proxy:1.8.1
//JAVAC_OPTIONS -parameters
//JAVA_OPTIONS -Djava.util.logging.manager=org.jboss.logmanager.LogManager
//Q:CONFIG quarkus.banner.enabled=false
//Q:CONFIG quarkus.log.level=INFO
//Q:CONFIG quarkus.log.min-level=DEBUG
//Q:CONFIG quarkus.log.category."net.ttddyy.dsproxy.listener.logging".level=DEBUG
//Q:CONFIG quarkus.log.category."LogRecordWriterService".level=INFO
//Q:CONFIG quarkus.log.console.stderr=true
//Q:CONFIG quarkus.kubernetes-client.trust-certs=false
//Q:CONFIG quarkus.kubernetes-client.namespace=default
//Q:CONFIG quarkus.datasource.db-kind=postgresql

import java.io.BufferedReader;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.DelayQueue;
import java.util.concurrent.Delayed;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.UnknownHostException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.enterprise.context.Dependent;
import javax.enterprise.inject.Alternative;
import javax.enterprise.inject.Produces;
import javax.inject.Named;
import javax.inject.Singleton;
import javax.sql.DataSource;

import picocli.CommandLine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.agroal.api.AgroalDataSource;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodStatus;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.Watch;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.WatcherException;
import io.fabric8.kubernetes.client.dsl.PodResource;
import io.quarkus.arc.Priority;
import io.quarkus.arc.profile.IfBuildProfile;
import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.CommandLineArguments;
import io.quarkus.runtime.annotations.QuarkusMain;

import net.ttddyy.dsproxy.listener.logging.OutputParameterLogEntryCreator;
import net.ttddyy.dsproxy.listener.logging.SLF4JQueryLoggingListener;
import net.ttddyy.dsproxy.support.ProxyDataSource;
import net.ttddyy.dsproxy.support.ProxyDataSourceBuilder;

@Singleton
@lombok.Getter
class IngestCommand
{
    @CommandLine.Option(names = { "-h", "--help" }, usageHelp = true)
    protected boolean helpRequested = false;
    
    @CommandLine.Option(names = {"--label-selector", "-l"}, 
        defaultValue = "app.kubernetes.io/name=ingress-nginx", 
        showDefaultValue = CommandLine.Help.Visibility.ALWAYS)
    protected String labelSelector;
    
    @CommandLine.Option(names = {"--namespace", "-n"}, 
        defaultValue = "ingress-nginx", 
        showDefaultValue = CommandLine.Help.Visibility.ALWAYS)
    protected String namespace;
    
    @CommandLine.Option(names = {"--reader-delay-seconds"}, defaultValue = "15")
    protected long readerDelaySeconds;
    
    @CommandLine.Option(names = {"--reader-initial-delay-seconds"}, defaultValue = "5")
    protected long readerInitialDelaySeconds;
    
    @CommandLine.Option(names = {"--since-time", "-t"}, defaultValue = "1970-01-01T00:00:00Z")
    protected ZonedDateTime sinceTime;
    
    protected Pattern serviceNamePattern;
    
    @CommandLine.Option(names = {"--service-name", "-s"}, 
        defaultValue = "geoserver-s?", 
        showDefaultValue = CommandLine.Help.Visibility.ALWAYS, 
        description = "A (glob) pattern for the service name")
    void setServiceNamePattern(String glob) 
    {
        if (glob.equals("*"))
            serviceNamePattern = Pattern.compile(".+");
        else 
            serviceNamePattern = Pattern.compile(
                Pattern.quote(glob).replace("*", "\\E.*\\Q").replace("?", "\\E.\\Q"));
    }
    
    public IngestCommand(@CommandLineArguments String[] args)
    {
        final CommandLine commandLine = new CommandLine(this);
        final CommandLine.ParseResult r = commandLine.parseArgs(args);
        
        if (r.isUsageHelpRequested()) {
            commandLine.usage(System.out);
        }
    }
}

@QuarkusMain
public class Ingest implements QuarkusApplication
{
    protected static final Logger LOGGER = LoggerFactory.getLogger(Ingest.class);
    
    private final IngestCommand command;
    
    private final PodWatcherService podWatcherService;
        
    public Ingest(IngestCommand command, PodWatcherService podWatcherService)
    {
        this.command = command;
        this.podWatcherService = podWatcherService;
    }
        
    @Override
    public int run(String... args) throws Exception
    {
        if (command.isHelpRequested()) {
            return 0;
        }
        podWatcherService.start();
        podWatcherService.awaitWatchClosed();
        Quarkus.blockingExit();
        return 0;
    }
}

@Dependent
class PodWatcherService implements  Watcher<Pod>
{
    private static final Logger LOGGER = LoggerFactory.getLogger(PodWatcherService.class);
    
    protected static final int READER_THREAD_POOL_SIZE = 6;
    
    protected static final int READER_TASK_EXECUTOR_TERMINATION_TIMEOUT_SECONDS = 5;
    
    protected static final int READER_MAX_RETRY_COUNT = 3;
    
    private final KubernetesClient kubernetesClient;
    
    private final Consumer<LogRecord> recordProcessingService;
    
    private final String namespace;
    
    private final String labelSelector;
    
    private final ZonedDateTime sinceTime;
    
    private final long readerDelaySeconds;
    
    private final long readerInitialDelaySeconds;
    
    private final ScheduledExecutorService readerTaskExecutor;
    
    private final ConcurrentMap<String, ScheduledFuture<?>> readerFuturesByPodName;
    
    private final CountDownLatch watchClosedLatch;
    
    private Watch watch;
    
    public PodWatcherService(
        KubernetesClient kubernetesClient, 
        @Named("logRecordProcessingService") Consumer<LogRecord> recordProcessingService, 
        IngestCommand command)
    {
        this.kubernetesClient = kubernetesClient;
        this.recordProcessingService = recordProcessingService;
        this.namespace = command.getNamespace();
        this.labelSelector = command.getLabelSelector();
        this.sinceTime = command.getSinceTime();
        this.readerDelaySeconds = command.getReaderDelaySeconds();
        this.readerInitialDelaySeconds = command.getReaderInitialDelaySeconds();
        this.readerTaskExecutor = Executors.newScheduledThreadPool(READER_THREAD_POOL_SIZE);
        this.readerFuturesByPodName = new ConcurrentHashMap<>();
        this.watchClosedLatch = new CountDownLatch(1);
        this.watch = null;
    }

    public synchronized void start()
    {
        // Start watcher
        watch = kubernetesClient.pods()
            .inNamespace(namespace).withLabel(labelSelector)
            .watch(this);
        LOGGER.info("Watching namespace [{}] for pods with label: {}", 
            namespace, labelSelector);
    }
    
    public void awaitWatchClosed() throws InterruptedException
    {
        watchClosedLatch.await();
        
        synchronized (this) {
            if (watch != null) {
                watch.close();
                watch = null;
            }
        }
    }
    
    @PreDestroy
    void preDestroy()
    {
        // Stop readers
        LOGGER.info("Shutting down executor for readers...");
        readerTaskExecutor.shutdown();  // stop accepting new reader tasks
        try {
            readerTaskExecutor.awaitTermination(READER_TASK_EXECUTOR_TERMINATION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            LOGGER.error("reader task executor was interrupted while terminating", ex);
        }
    }
    
    @Override
    public void eventReceived(Action action, Pod pod)
    {
        final var podMetadata = pod.getMetadata();
        final String podName = podMetadata.getName();
        switch (action) {
        case ADDED:
            {
                LOGGER.info("Adding a reader for pod [podName={}]", podName);
                final PodLogsReader reader = new PodLogsReader(podName, sinceTime);
                final ScheduledFuture<?> fu = readerTaskExecutor.scheduleWithFixedDelay(reader, 
                    readerInitialDelaySeconds, readerDelaySeconds, TimeUnit.SECONDS);
                readerFuturesByPodName.put(podName, fu);
            }
            break;
        case DELETED:
            {
                LOGGER.info("Deleting reader for pod [podName={}]", podName);
                final ScheduledFuture<?> fu = readerFuturesByPodName.remove(podName);
                if (fu != null) {
                    if (fu.isDone()) {
                        // no-op
                    } if (fu.cancel(false)) {
                        LOGGER.info("Canceled reader task for pod [podName={}]", podName);
                    } else {
                        LOGGER.warn("Failed to cancel reader task for pod [podName={}]", podName);
                    }
                } else {
                    LOGGER.warn("Did not find a future for pod [podName={}]", podName);
                }
            }
            break;
        case MODIFIED:
            // no-op
            break;
        default:
            // no-op
            break;
        }
    }

    @Override
    public void onClose(WatcherException cause)
    {
        LOGGER.info("watch is closed");
        watchClosedLatch.countDown();
    }
    
    class PodLogsReader implements Runnable {

        private final Logger logger = LoggerFactory.getLogger(getClass());
        
        private final String podName;
        
        private ZonedDateTime sinceTime;
        
        private int retryCount;
        
        public PodLogsReader(String podName, ZonedDateTime sinceTime)
        {
            this.podName = podName;
            this.sinceTime = sinceTime;
            this.retryCount = 0;
        }
        
        @Override
        public void run()
        {
            logger.info("Reading more logs from pod [podName={}, sinceTime={}, retryCount={}]", 
                podName, sinceTime, retryCount);
            
            final PodResource<Pod> podResource = kubernetesClient.pods()
                .inNamespace(namespace).withName(podName);
            final Pod pod = podResource.get();
            
            final PodStatus podStatus = pod.getStatus();
            // If pod is not running yet, skip this execution
            if (podStatus.getPhase().equals("Pending")) { // see kubectl explain pod.status.phase
                logger.info("Skipping execution of log reader [podName={}, pod.status.phase={}]", 
                    podName, podStatus.getPhase());
                return;
            }
            
            var podLogs = podResource.usingTimestamps()
                .sinceTime(sinceTime.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
            try (BufferedReader reader = new BufferedReader(podLogs.getLogReader())) {
                String line = null;
                while ((line = reader.readLine()) != null) {
                    //System.err.println("  >> " + line);
                    String[] fields = line.split("\\s+", 2);
                    ZonedDateTime time = null;
                    try { 
                        time = ZonedDateTime.parse(fields[0]);
                    } catch (DateTimeParseException ex) {
                        logger.info("Skipping line with invalid timestamp:\n >>{}", line);
                        continue;
                    }
                    // Parse record
                    LogRecord record = null;
                    try {
                        record = LogParser.parseLine(fields[1]);
                    } catch (IllegalArgumentException ex) {
                        if (logger.isDebugEnabled()) {
                            logger.debug("Failed to parse line: {}:\n  >>{}", ex.getMessage(), line);
                        }
                        continue;
                    }
                    // Process record
                    //System.err.println("  >>" + record.toString());
                    recordProcessingService.accept(record); 
                    // Update time for last seen record
                    sinceTime = time;
                }
            } catch (Exception ex) {
                logger.info("An exception happened while reading logs", ex);
                if (++retryCount == READER_MAX_RETRY_COUNT) {
                    logger.error("Aborting reader task (reached max  number of retries)");
                    throw new IllegalStateException(ex);
                }
            }
        }
    } 
}

@Dependent
@Named("logRecordProcessingService")
class LogRecordProcessingService implements Consumer<LogRecord> {
    
    // A pattern for URIs that must be excluded from processibg
    // (e.g pattern for the external-auth URL used by Nginx for auth subrequest) 
    protected static final Pattern URI_EXCLUDE_PATTERN = Pattern.compile("([/]_external-auth-[\\w]+-Prefix)");
    
    protected static final List<String> METHODS_TO_EXCLUDE = Arrays.asList("OPTIONS", "HEAD");
    
    protected static final List<Integer> SUCCESS_STATUSES = Arrays.asList(200, 201, 202, 204);
    
    private final Pattern serviceNamePattern;
    
    private final Consumer<LogRecord> recordWriterService;
    
    public LogRecordProcessingService(
        IngestCommand command, @Named("logRecordWriterService") Consumer<LogRecord> recordWriterService)
    {
        this.serviceNamePattern = command.getServiceNamePattern();
        this.recordWriterService = recordWriterService;
    }
    
    @Override
    public void accept(LogRecord record)
    {
        // Filter-out records not related to our service 
        
        if (record.requestId == null) {
            throw new IllegalStateException("requestId is null!");
        }
        
        if (METHODS_TO_EXCLUDE.contains(record.requestMethod))
            return;
        
        if (URI_EXCLUDE_PATTERN.matcher(record.uri.getPath()).matches())
            return;
        
        if (!serviceNamePattern.matcher(record.serviceName).matches())
            return;
        
        if (record.status == null || !SUCCESS_STATUSES.contains(record.status.intValue()))
            return;
        
        if (record.upstreamResponseStatus == null)
            return;
        
        // Dispatch record to our consumer
        //System.err.println(" <<" + record.toString());
        recordWriterService.accept(record);
    }
}

@Dependent
@Named("logRecordWriterService")
class LogRecordWriterService implements Consumer<LogRecord>
{
    private static final Logger LOGGER = LoggerFactory.getLogger(LogRecordWriterService.class);
    
    protected static final int OUTPUT_QUEUE_DELAY_SECONDS = 20;
    
    protected static final int WRITER_TASK_EXECUTOR_TERMINATION_TIMEOUT_SECONDS = OUTPUT_QUEUE_DELAY_SECONDS + 3;
    
    protected static final int POLL_TIMEOUT_SECONDS = 2;
    
    protected static final int BATCH_MAX_SIZE = 15;
    
    protected static final int BATCH_UPDATE_TIMEOUT_MILLIS = 25 * 1000;
        
    private final DataSource dataSource;
    
    private final ExecutorService writerTaskExecutor;
    
    private final DelayQueue<DelayedItem<LogRecord>> outputQueue;
    
    private final Set<RequestStats> batch;
    
    private volatile boolean stopRequested = false;
    
    public LogRecordWriterService(DataSource dataSource)
    {
        this.dataSource = dataSource;
        this.writerTaskExecutor = Executors.newSingleThreadExecutor();
        this.outputQueue = new DelayQueue<>();
        this.batch = new HashSet<>(2 * BATCH_MAX_SIZE);
    }
    
    @PreDestroy
    void preDestroy()
    {
        LOGGER.info("Shutting down executor for writer...");
        writerTaskExecutor.shutdown();
        stopRequested = true;
        try {
            writerTaskExecutor.awaitTermination(WRITER_TASK_EXECUTOR_TERMINATION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            LOGGER.error("writer task executor was interrupted while terminating", ex);
        }
    }
    
    private void pingDataSource()
    {
        try (Connection conn = dataSource.getConnection();
            Statement st = conn.createStatement()) 
        {
            st.executeQuery("SELECT 1");
        } catch (SQLException ex) {
            throw new IllegalStateException(ex);
        } 
    }
    
    @PostConstruct
    void start()
    {        
        pingDataSource();
        writerTaskExecutor.submit(new LogRecordWriter());
    }
    
    @Override
    public void accept(LogRecord record)
    {
        LOGGER.debug("accepted one more record [record={}]", record);
        outputQueue.offer(DelayedItem.of(record, OUTPUT_QUEUE_DELAY_SECONDS));
    }
    
    class LogRecordWriter implements Runnable
    {
        @Override
        public void run()
        {
            long now = System.currentTimeMillis();
            batch.clear();
            long lastUpdate = now;
            
            boolean done = false;
            DelayedItem<LogRecord> delayed;
            do {
                LOGGER.debug("Polling queue...");
                try {
                    delayed = outputQueue.poll(POLL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                } catch (InterruptedException ex) {
                    LOGGER.error("interrupted while polling the output queue", ex);
                    delayed = null;
                }
                if (delayed != null) {
                    final LogRecord record = delayed.unwrap();
                    //System.out.println("  <<" + record);
                    batch.add(record);
                } else {
                    // no-op: poll has timed out or was interrupted 
                }
                done = stopRequested && outputQueue.isEmpty();
                now = System.currentTimeMillis();
                if (batch.size() == BATCH_MAX_SIZE 
                        || (batch.size() > 0 && (done || now - lastUpdate > BATCH_UPDATE_TIMEOUT_MILLIS))) {
                    lastUpdate = now;
                    updateForBatch(batch);
                    batch.clear();
                }
            } while (!done);
        }
        
        private static final String UPDATE_SQL = 
            "UPDATE web.account_client_request SET started = ?, took = ?, response_size = ?"
            + " WHERE request_id = ? AND started is null";
        
        private void updateForBatch(Collection<RequestStats> records) 
        {
            LOGGER.info("Updating from a batch of {} records", records.size());
            try (
                Connection conn = dataSource.getConnection(); 
                PreparedStatement updateStatement = conn.prepareStatement(UPDATE_SQL)) 
            {
                conn.setAutoCommit(false);
                for (RequestStats record: records) {
                    setParametersForUpdateStatement(updateStatement, record);
                    updateStatement.addBatch();
                }
                updateStatement.executeBatch();
                conn.commit();
            } catch (Exception ex) {
                LOGGER.error("Failed to update with batch of records", ex);
                throw new IllegalStateException(ex);
            }
        }
        
        private void setParametersForUpdateStatement(PreparedStatement updateStatement, RequestStats record) 
            throws SQLException
        {
            updateStatement.clearParameters();
            updateStatement.setTimestamp(1, java.sql.Timestamp.from(record.getTimestamp().toInstant()));
            updateStatement.setInt(2, record.getResponseTimeInMillis());
            updateStatement.setInt(3, record.getResponseSizeInBytes());
            updateStatement.setString(4, record.getRequestId());
        }
    }
}

interface Timestamped
{
    ZonedDateTime getTimestamp();
}

interface RequestStats extends Timestamped
{
    String getRequestId();
    
    Integer getResponseTimeInMillis();
    
    Integer getResponseSizeInBytes();
}

@lombok.NoArgsConstructor
@lombok.Getter
@lombok.Setter
@lombok.ToString
@lombok.EqualsAndHashCode(onlyExplicitlyIncluded = true)
class LogRecord implements RequestStats
{
    protected InetAddress remoteAddr;
    
    protected String userId;
    
    protected String userName;
    
    protected ZonedDateTime timestamp;
    
    protected String vhost;
    
    protected String serviceName;
    
    protected URI requestUri;
    
    // internal URI, different from requestUri in internal redirects (e.g. external auth)
    protected URI uri; 
    
    protected String requestMethod;
    
    protected Integer status;
    
    protected Integer responseBodyLen;
    
    protected URL refererUrl;
 
    protected String userAgent;
    
    protected Integer requestLen;
    
    protected Float requestTime;
    
    protected String upstreamName;
    
    protected String upstreamAddr;
    
    protected Integer upstreamResponseLen;
    
    protected Float upstreamResponseTime;
    
    protected Integer upstreamResponseStatus;
    
    @lombok.EqualsAndHashCode.Include
    protected String requestId;

    @Override
    public Integer getResponseTimeInMillis()
    {
        return upstreamResponseTime == null? null : 
            Math.round(upstreamResponseTime.floatValue() * 1000); 
    }

    @Override
    public Integer getResponseSizeInBytes()
    {
        return upstreamResponseLen;
    }
}

class DelayedItem <T extends Timestamped> implements Delayed 
{
    private final T item;
    private final long expiresAt; // Epoch seconds
    
    DelayedItem(T item, long delaySeconds) {
        this.item = item;
        this.expiresAt = item.getTimestamp().toEpochSecond() + delaySeconds;
    }
    
    public static <U extends Timestamped> DelayedItem<U> of(U item, long delaySeconds) {
        return new DelayedItem<U> (item, delaySeconds);
    }
    
    public T unwrap() {
        return item;
    }
    
    @SuppressWarnings("unchecked")
    @Override
    public int compareTo(Delayed other) {
        return Long.compare(expiresAt, ((DelayedItem<T>) other).expiresAt);
    }

    @Override
    public long getDelay(TimeUnit unit) {
        return unit.convert(Duration.ofSeconds(expiresAt - Instant.now().getEpochSecond()));
    }
}

class LogParser {
    
    // https://kubernetes.github.io/ingress-nginx/user-guide/nginx-configuration/log-format/
    // https://nginx.org/en/docs/http/ngx_http_core_module.html#variables
    private static final Pattern LINE_PATTERN = Pattern.compile(
        "(?<remoteAddr>(?:(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?))"
        + "\\s(?<userId>[-]|(?:\\w+))"
        + "\\s(?<userName>[-]|(?:[\\w][\\.\\w]*))"
        + "\\s\\[(?<timestamp>[^\\]]+)\\]"
        + "\\s(?<vhost>[-\\w]+(?:\\.[-\\w]+)+)"
        + "\\s(?<requestMethod>GET|OPTIONS|HEAD|POST|PUT|PATCH|DELETE)"
        + "\\s\"(?<requestUri>[^\"]+)\""
        + "\\s\"(?<uri>[^\"]+)\""
        + "\\s(?<status>[1-5][0-9]{2})"
        + "\\s(?<responseBodyLen>[0-9]+)"
        + "\\s\"(?<referer>[^\"]+)\""
        + "\\s\"(?<userAgent>[^\"]+)\""
        + "\\s(?<requestLen>[0-9]+)"
        + "\\s(?<requestTime>[0-9]+\\.[0-9]{3})"
        + "\\s\\[(?<upstreamName>[^\\]]+)\\]"
        + "\\s\\[(?<upstreamName1>[^\\]]*)\\]"
        + "\\s(?<serviceName>\\w[-\\w]+)"
        + "\\s(?<upstreamAddr>[-]|(?:(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)[:](?:[1-9][0-9]*))"
        + "\\s(?<upstreamResponseLen>[-]|[0-9]+)"
        + "\\s(?<upstreamResponseTime>[-]|[0-9]+\\.[0-9]{3})"
        + "\\s(?<upstreamResponseStatus>[-]|[1-5][0-9]{2})"
        + "\\s(?<requestId>[a-f0-9]{32})");

    private static final DateTimeFormatter TIMESTAMP_FORMATTER =  DateTimeFormatter.ofPattern("dd/MMM/yyyy:HH:mm:ss Z");
    
    private static final String NULL_STRING = "-";    
    
    static LogRecord parseLine(String line) 
        throws UnknownHostException, URISyntaxException, MalformedURLException
    {
        if (line == null || line.isBlank()) {
            throw new IllegalArgumentException("line is empty!");
        }
        
        final Matcher matcher = LINE_PATTERN.matcher(line);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("line does not match expected pattern");
        }
        
        final LogRecord record = new LogRecord();
        
        final String remoteAddrAsString = matcher.group("remoteAddr");
        record.remoteAddr = InetAddress.getByName(remoteAddrAsString);
        
        final String userId = matcher.group("userId"); 
        record.userId = userId.equals(NULL_STRING)? null : userId;
        
        final String userName = matcher.group("userName");
        record.userName = userName.equals(NULL_STRING)? null : userName;
        
        final String timestampAsString =  matcher.group("timestamp");
        record.timestamp = ZonedDateTime.parse(timestampAsString, TIMESTAMP_FORMATTER);
        
        final String requestMethod = matcher.group("requestMethod");
        record.requestMethod = requestMethod;
      
        final String vhost = matcher.group("vhost");
        record.vhost = vhost; 
        
        final String requestUriAsString = matcher.group("requestUri");
        record.requestUri = URI.create(requestUriAsString);
        
        final String uriAsString = matcher.group("uri");
        record.uri = URI.create(uriAsString);
        
        final String statusAsString = matcher.group("status");
        record.status = Integer.parseInt(statusAsString);
        
        final String responseBodyLenAsString = matcher.group("responseBodyLen");
        record.responseBodyLen = Integer.parseInt(responseBodyLenAsString);
        
        final String refererUrl = matcher.group("referer");
        record.refererUrl = refererUrl.equals(NULL_STRING)? null : new URL(refererUrl);
        
        final String userAgent = matcher.group("userAgent");
        record.userAgent = userAgent;
        
        final String requestLenAsString = matcher.group("requestLen");
        record.requestLen = Integer.parseInt(requestLenAsString);
        
        final String requestTimeAsString = matcher.group("requestTime");
        record.requestTime = Float.parseFloat(requestTimeAsString);
        
        final String upstreamName = matcher.group("upstreamName");
        record.upstreamName = upstreamName.equals(NULL_STRING)? null : upstreamName;
        
        final String upstreamAddr = matcher.group("upstreamAddr");
        record.upstreamAddr = upstreamAddr.equals(NULL_STRING)? null : upstreamAddr;
        
        final String upstreamResponseLenAsString = matcher.group("upstreamResponseLen");
        record.upstreamResponseLen = upstreamResponseLenAsString.equals(NULL_STRING)? null :
            Integer.parseInt(upstreamResponseLenAsString);
        
        final String upstreamResponseTimeAsString = matcher.group("upstreamResponseTime");
        record.upstreamResponseTime = upstreamResponseTimeAsString.equals(NULL_STRING)? null :
            Float.parseFloat(upstreamResponseTimeAsString);
        
        final String upstreamResponseStatusAsString = matcher.group("upstreamResponseStatus");
        record.upstreamResponseStatus = upstreamResponseStatusAsString.equals(NULL_STRING)? null :
            Integer.parseInt(upstreamResponseStatusAsString);
        
        final String serviceName = matcher.group("serviceName");
        record.serviceName = serviceName;
        
        final String requestId = matcher.group("requestId");
        record.requestId = requestId;
        
        return record;
    }
}

@Singleton
class DataSourceConfiguration
{   
    @Produces
    @IfBuildProfile("dev")
    @Alternative
    @Priority(1)
    public ProxyDataSource loggingDataSource(AgroalDataSource dataSource)
    {
        SLF4JQueryLoggingListener loggingListener = new SLF4JQueryLoggingListener();
        loggingListener.setQueryLogEntryCreator(new OutputParameterLogEntryCreator());
        return ProxyDataSourceBuilder.create(dataSource).name("Datasource-Proxy")
            .listener(loggingListener).build();
    }
}
