package org.framework.starter.resttemplate.configuration;

import lombok.extern.slf4j.Slf4j;
import org.apache.http.Header;
import org.apache.http.HeaderElement;
import org.apache.http.HeaderElementIterator;
import org.apache.http.HttpHost;
import org.apache.http.client.HttpClient;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.config.Registry;
import org.apache.http.config.RegistryBuilder;
import org.apache.http.conn.ConnectionKeepAliveStrategy;
import org.apache.http.conn.socket.ConnectionSocketFactory;
import org.apache.http.conn.socket.PlainConnectionSocketFactory;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.DefaultHttpRequestRetryHandler;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.message.BasicHeader;
import org.apache.http.message.BasicHeaderElementIterator;
import org.apache.http.protocol.HTTP;
import org.apache.http.ssl.SSLContextBuilder;
import org.framework.starter.resttemplate.core.client.CustomRestTemplateCustomizer;
import org.framework.starter.resttemplate.properties.HttpClientPoolProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.RestTemplate;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import java.nio.charset.Charset;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.util.*;

@Configuration
@ConditionalOnProperty(prefix = "http-client.pool" , name = "enabled" , havingValue = "true")
@ConditionalOnClass({HttpClientPoolProperties.class,RestTemplate.class,CloseableHttpClient.class})
@EnableConfigurationProperties({HttpClientPoolProperties.class})
@Slf4j
public class RestTemplateAutoConfiguration {

    private final HttpClientPoolProperties httpClientPoolProperties;

    public RestTemplateAutoConfiguration(HttpClientPoolProperties httpClientPoolProperties) {
        this.httpClientPoolProperties = httpClientPoolProperties;
    }

    @Bean
    public CustomRestTemplateCustomizer customRestTemplateCustomizer() {
        return new CustomRestTemplateCustomizer();
    }

    @Bean
    @DependsOn(value = {"customRestTemplateCustomizer"})
    public RestTemplateBuilder restTemplateBuilder() {
        return new RestTemplateBuilder(customRestTemplateCustomizer());
    }

    @Bean
    public ClientHttpRequestFactory clientHttpRequestFactory() {
        if (httpClientPoolProperties.getMaxTotalConnect() <= 0) {
            throw new IllegalArgumentException("invalid maxTotalConnection: " + httpClientPoolProperties.getMaxTotalConnect());
        }
        if (httpClientPoolProperties.getMaxConnectPerRoute() <= 0) {
            throw new IllegalArgumentException("invalid maxConnectionPerRoute: " + httpClientPoolProperties.getMaxConnectPerRoute());
        }
        HttpComponentsClientHttpRequestFactory factory = new HttpComponentsClientHttpRequestFactory (httpClient());
        // 数据读取超时时间，即SocketTimeout
        factory.setReadTimeout(httpClientPoolProperties.getReadTimeout());
        // 连接超时
        factory.setConnectTimeout(httpClientPoolProperties.getConnectTimeout());
        // 请求连接的超时时间
        factory.setConnectionRequestTimeout(httpClientPoolProperties.getConnectionRequestTimeout());
        return factory;
    }

    @Bean
    public RestTemplate restTemplate(RestTemplateBuilder builder ,ClientHttpRequestFactory factory) {
        RestTemplate restTemplate = builder.build();
        restTemplate.setRequestFactory(factory);
        //设置错误处理器
        restTemplate.setErrorHandler(new DefaultResponseErrorHandler());
        // 重新设置StringHttpMessageConverter字符集
        modifyDefaultCharset(restTemplate);
        return restTemplate;
    }

    @Bean
    public HttpClient httpClient() {
        HttpClientBuilder httpClientBuilder = HttpClientBuilder.create();
        try {
            //设置信任ssl访问
            SSLContext sslContext = new SSLContextBuilder().loadTrustMaterial(null, (arg0, arg1) -> true).build();

            httpClientBuilder.setSSLContext(sslContext);
            HostnameVerifier hostnameVerifier = NoopHostnameVerifier.INSTANCE;
            SSLConnectionSocketFactory sslConnectionSocketFactory = new SSLConnectionSocketFactory(sslContext, hostnameVerifier);
            Registry<ConnectionSocketFactory> socketFactoryRegistry = RegistryBuilder.<ConnectionSocketFactory>create()
                    // 注册http和https请求
                    .register("http", PlainConnectionSocketFactory.getSocketFactory())
                    .register("https", sslConnectionSocketFactory).build();

            // 使用Httpclient连接池的方式配置(推荐)，同时支持netty，okHttp以及其他http框架
            PoolingHttpClientConnectionManager poolingHttpClientConnectionManager = new PoolingHttpClientConnectionManager(socketFactoryRegistry);
            // 设置最大连接数
            poolingHttpClientConnectionManager.setMaxTotal(httpClientPoolProperties.getMaxTotalConnect());
            // 设置同路由并发数
            poolingHttpClientConnectionManager.setDefaultMaxPerRoute(httpClientPoolProperties.getMaxConnectPerRoute());
            // 配置连接池
            httpClientBuilder.setConnectionManager(poolingHttpClientConnectionManager);
            // 设置重试次数
            httpClientBuilder.setRetryHandler(new DefaultHttpRequestRetryHandler(httpClientPoolProperties.getRetryTimes(), true));

            // 设置默认请求头
            List<Header> headers = getDefaultHeaders();
            httpClientBuilder.setDefaultHeaders(headers);
            // 设置长连接保持策略
            httpClientBuilder.setKeepAliveStrategy(connectionKeepAliveStrategy());
            return httpClientBuilder.build();
        } catch (KeyManagementException | NoSuchAlgorithmException | KeyStoreException e) {
            log.error("HttpClient exception {}", e.getMessage());
        }
        return null;
    }

    /**
     * 配置长连接保持策略 <br>
     * @Date 2022/05/06 9:57
     * @return org.apache.http.conn.ConnectionKeepAliveStrategy
     */
    public ConnectionKeepAliveStrategy connectionKeepAliveStrategy(){
        return (response, context) -> {
            // 'keep-alive' header
            HeaderElementIterator it = new BasicHeaderElementIterator(
                    response.headerIterator(HTTP.CONN_KEEP_ALIVE));
            while (it.hasNext()) {
                HeaderElement he = it.nextElement();
                String param = he.getName();
                String value = he.getValue();
                if (value != null && "timeout".equalsIgnoreCase(param)) {
                    try {
                        return Long.parseLong(value) * 1000;
                    } catch(NumberFormatException e) {
                        log.error("NumberFormatException {}", e.getMessage());
                    }
                }
            }
            HttpHost target = (HttpHost) context.getAttribute(HttpClientContext.HTTP_TARGET_HOST);
            // 如果请求目标地址,单独配置了长连接保持时间,使用该配置
            Optional<Map.Entry<String, Integer>> any = Optional.ofNullable(httpClientPoolProperties.getKeepAliveTargetHost())
                    .orElseGet(HashMap::new)
                    .entrySet().stream()
                    .filter(e -> e.getKey().equalsIgnoreCase(target.getHostName()))
                    .findAny();
            // 否则使用默认长连接保持时间
            return any.map(en -> en.getValue() * 1000L)
                    .orElse(httpClientPoolProperties.getKeepAliveTime() * 1000L);
        };
    }

    /**
     * 设置请求头
     *
     * @return 请求头
     */
    private List<Header> getDefaultHeaders() {
        List<Header> headers = new ArrayList<>();
        headers.add(new BasicHeader("User-Agent",
                "Mozilla/5.0 (Windows NT 6.1) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/31.0.1650.16 Safari/537.36"));
        headers.add(new BasicHeader("Accept-Encoding", "gzip,deflate"));
        headers.add(new BasicHeader("Accept-Language", "zh-CN"));
        headers.add(new BasicHeader("Connection", "Keep-Alive"));
        return headers;
    }

    private void modifyDefaultCharset(RestTemplate restTemplate) {
        List<HttpMessageConverter<?>> converterList = restTemplate.getMessageConverters();
        HttpMessageConverter<?> converterTarget = null;
        for (HttpMessageConverter<?> item : converterList) {
            if (StringHttpMessageConverter.class == item.getClass()) {
                converterTarget = item;
                break;
            }
        }
        if (null != converterTarget) {
            converterList.remove(converterTarget);
        }
        Charset defaultCharset = Charset.forName(httpClientPoolProperties.getCharset());
        converterList.add(1, new StringHttpMessageConverter(defaultCharset));
    }

}
