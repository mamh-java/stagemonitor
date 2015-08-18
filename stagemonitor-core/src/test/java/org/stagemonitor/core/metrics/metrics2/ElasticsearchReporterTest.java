package org.stagemonitor.core.metrics.metrics2;

import static java.util.Collections.singletonMap;
import static org.junit.Assert.assertEquals;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyMap;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.stagemonitor.core.metrics.MetricsReporterTestHelper.counter;
import static org.stagemonitor.core.metrics.MetricsReporterTestHelper.gauge;
import static org.stagemonitor.core.metrics.MetricsReporterTestHelper.histogram;
import static org.stagemonitor.core.metrics.MetricsReporterTestHelper.map;
import static org.stagemonitor.core.metrics.MetricsReporterTestHelper.meter;
import static org.stagemonitor.core.metrics.MetricsReporterTestHelper.metricNameMap;
import static org.stagemonitor.core.metrics.MetricsReporterTestHelper.objectMap;
import static org.stagemonitor.core.metrics.MetricsReporterTestHelper.timer;
import static org.stagemonitor.core.metrics.metrics2.MetricName.name;

import java.io.ByteArrayOutputStream;
import java.net.HttpURLConnection;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

import com.codahale.metrics.Clock;
import com.codahale.metrics.Counter;
import com.codahale.metrics.Gauge;
import com.codahale.metrics.Histogram;
import com.codahale.metrics.Meter;
import com.codahale.metrics.Timer;
import org.junit.Before;
import org.junit.Test;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.stagemonitor.core.CorePlugin;
import org.stagemonitor.core.util.HttpClient;
import org.stagemonitor.core.util.JsonUtils;
import org.stagemonitor.core.util.StringUtils;

public class ElasticsearchReporterTest {

	private ElasticsearchReporter elasticsearchReporter;
	private long timestamp;
	private ByteArrayOutputStream out;

	@Before
	public void setUp() throws Exception {
		final Clock clock = mock(Clock.class);
		timestamp = System.currentTimeMillis();
		when(clock.getTime()).thenReturn(timestamp);
		final HttpClient httpClient = mock(HttpClient.class);
		when(httpClient.send(anyString(), anyString(), anyMap(), any(HttpClient.HttpURLConnectionHandler.class))).thenAnswer(new Answer<Integer>() {
			@Override
			public Integer answer(InvocationOnMock invocation) throws Throwable {
				HttpClient.HttpURLConnectionHandler handler = (HttpClient.HttpURLConnectionHandler) invocation.getArguments()[3];
				final HttpURLConnection connection = mock(HttpURLConnection.class);
				when(connection.getOutputStream()).thenReturn(out);
				handler.withHttpURLConnection(connection);
				return 200;
			}
		});
		elasticsearchReporter = new ElasticsearchReporter(mock(Metric2Registry.class),
				Metric2Filter.ALL, TimeUnit.SECONDS, TimeUnit.NANOSECONDS, singletonMap("app", "test"),
				httpClient, clock, mock(CorePlugin.class));
		out = new ByteArrayOutputStream();
	}

	@Test
	public void testReportGauges() throws Exception {
		elasticsearchReporter.reportMetrics(
				metricNameMap(
						name("cpu_usage").type("user").tag("core", "1").build(), gauge(3),
						name("gauge2").build(), gauge("foo")
				),
				metricNameMap(Counter.class),
				metricNameMap(Histogram.class),
				metricNameMap(Meter.class),
				metricNameMap(Timer.class));

		final String jsons = new String(out.toByteArray());
		assertEquals(jsons, 4, jsons.split("\n").length);

		assertEquals(
				objectMap("index", map("_type", "metrics")
						.add("_index", "stagemonitor-metrics-" + StringUtils.getLogstashStyleDate())),
				asMap(jsons.split("\n")[0]));

		assertEquals(
				objectMap("@timestamp", timestamp)
						.add("name", "cpu_usage")
						.add("tags", map("app", "test")
								.add("type", "user")
								.add("core", "1"))
						.add("values", map("value", 3.0)),
				asMap(jsons.split("\n")[1]));

		// only number-gauges are supported
		// in elasticsearch a field can only have one datatype
		// the values.value field has the type double so it can't store strings
		assertEquals(
				objectMap("@timestamp", timestamp)
						.add("name", "gauge2")
						.add("tags", map("app", "test"))
						.add("values", map()),
				asMap(jsons.split("\n")[3]));
	}

	@Test
	public void testReportCounters() throws Exception {
		elasticsearchReporter.reportMetrics(
				metricNameMap(Gauge.class),
				metricNameMap(name("web_sessions").build(), counter(123)),
				metricNameMap(Histogram.class),
				metricNameMap(Meter.class),
				metricNameMap(Timer.class));

		assertEquals(
				map("@timestamp", timestamp, Object.class)
						.add("name", "web_sessions")
						.add("tags", map("app", "test"))
						.add("values", map("count", 123)),
				asMap(out));
	}

	@Test
	public void testReportHistograms() throws Exception {
		elasticsearchReporter.reportMetrics(
				metricNameMap(Gauge.class),
				metricNameMap(Counter.class),
				metricNameMap(name("histogram").build(), histogram(4)),
				metricNameMap(Meter.class),
				metricNameMap(Timer.class));

		assertEquals(objectMap("@timestamp", timestamp)
						.add("name", "histogram")
						.add("tags", map("app", "test"))
						.add("values", objectMap("count", 1)
								.add("max", 2)
								.add("mean", 4.0)
								.add("median", 6.0)
								.add("min", 4)
								.add("p25", 0.0)
								.add("p75", 7.0)
								.add("p95", 8.0)
								.add("p98", 9.0)
								.add("p99", 10.0)
								.add("p999", 11.0)
								.add("std", 5.0)),
				asMap(out));
	}

	@Test
	public void testReportMeters() throws Exception {
		elasticsearchReporter.reportMetrics(
				metricNameMap(Gauge.class),
				metricNameMap(Counter.class),
				metricNameMap(Histogram.class),
				metricNameMap(name("meter").build(), meter(10)),
				metricNameMap(Timer.class));

		assertEquals(map("@timestamp", timestamp, Object.class)
						.add("name", "meter")
						.add("tags", map("app", "test"))
						.add("values", objectMap("count", 10)
										.add("m15_rate", 5.0)
										.add("m1_rate", 3.0)
										.add("m5_rate", 4.0)
										.add("mean_rate", 2.0)
						),
				asMap(out));
	}

	@Test
	public void testReportTimers() throws Exception {
		elasticsearchReporter.reportMetrics(
				metricNameMap(Gauge.class),
				metricNameMap(Counter.class),
				metricNameMap(Histogram.class),
				metricNameMap(Meter.class),
				metricNameMap(name("response_time").build(), timer(4)));

		assertEquals(map("@timestamp", timestamp, Object.class)
						.add("name", "response_time")
						.add("tags", map("app", "test"))
						.add("values", objectMap("count", 1)
								.add("m15_rate", 5.0)
								.add("m1_rate", 3.0)
								.add("m5_rate", 4.0)
								.add("mean_rate", 2.0)
								.add("max", 2)
								.add("mean", 4.0)
								.add("median", 6.0)
								.add("min", 4)
								.add("p25", 0.0)
								.add("p75", 7.0)
								.add("p95", 8.0)
								.add("p98", 9.0)
								.add("p99", 10.0)
								.add("p999", 11.0)
								.add("std", 5.0)),
				asMap(out));
	}

	private Map<String, Object> asMap(ByteArrayOutputStream os) throws java.io.IOException {
		return asMap(new String(os.toByteArray()).split("\n")[1]);
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> asMap(String json) throws java.io.IOException {
		final TreeMap<String, Object> result = new TreeMap<String, Object>();
		result.putAll(JsonUtils.getMapper().readValue(json, Map.class));
		return result;
	}
}