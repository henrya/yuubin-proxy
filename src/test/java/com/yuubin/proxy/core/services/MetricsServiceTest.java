package com.yuubin.proxy.core.services;

import com.yuubin.proxy.config.YuubinProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

class MetricsServiceTest {

    private MetricsService metricsService;
    private int adminPort;

    @BeforeEach
    void setUp() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            adminPort = s.getLocalPort();
        }
        YuubinProperties props = new YuubinProperties();
        props.getAdmin().setPort(adminPort);
        props.getAdmin().setEnabled(true);
        metricsService = new MetricsService(props);
    }

    @AfterEach
    void tearDown() {
        if (metricsService != null) metricsService.shutdown();
    }

    @Test
    void health_returnsOk() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + adminPort + "/health"))
                .GET()
                .build();
        
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("OK");
    }

    @Test
    void metrics_returnsPrometheusData() throws Exception {
        metricsService.getRegistry().counter("test.counter").increment();
        
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + adminPort + "/metrics"))
                .GET()
                .build();
        
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("test_counter_total 1.0");
    }

    @Test
    void updateProperties_restartsServerOnPortChange() throws Exception {
        int newPort;
        try (ServerSocket s = new ServerSocket(0)) { newPort = s.getLocalPort(); }
        
        YuubinProperties props = new YuubinProperties();
        props.getAdmin().setPort(newPort);
        props.getAdmin().setEnabled(true);
        
        metricsService.updateProperties(props);
        
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + newPort + "/health"))
                .GET()
                .build();
        
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
    }
}
