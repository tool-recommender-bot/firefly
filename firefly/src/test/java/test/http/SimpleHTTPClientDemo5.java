package test.http;

import com.firefly.client.http2.SimpleHTTPClient;
import com.firefly.client.http2.SimpleHTTPClientConfiguration;
import com.firefly.net.SSLContextFactory;
import com.firefly.net.tcp.ssl.SelfSignedCertificateOpenSSLContextFactory;

import java.util.concurrent.ExecutionException;

/**
 * @author Pengtao Qiu
 */
public class SimpleHTTPClientDemo5 {

    public static void main(String[] args) throws ExecutionException, InterruptedException {
        SimpleHTTPClientConfiguration httpConfiguration = new SimpleHTTPClientConfiguration();
        SSLContextFactory sslContextFactory = new SelfSignedCertificateOpenSSLContextFactory();
//        SSLContextFactory sslContextFactory = new DefaultSSLContextFactory();
        httpConfiguration.setSslContextFactory(sslContextFactory);
        httpConfiguration.setSecureConnectionEnabled(true);

        SimpleHTTPClient client = new SimpleHTTPClient(httpConfiguration);

        for (int j = 0; j < 1; j++) {
            for (int i = 0; i < 1; i++) {
                long start = System.currentTimeMillis();
                client.get("https://www.baidu.com")
                      .submit()
                      .thenApply(res -> res.getStringBody("UTF-8"))
                      .thenAccept(System.out::println)
                      .thenAccept(v -> {
                          System.out.print("------------------------");
                          System.out.println("time: " + (System.currentTimeMillis() - start));
                      });
            }
            Thread.sleep(3000L);
        }
    }
}
