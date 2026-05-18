package com.dongwoo.stresscasdeep.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 시나리오 6 (CAS) — Connection leak detection.
 *
 * CAS 와 무관한 코드 규율 항목. 동일 측정.
 * DataSource.getConnection() 후 close 누락 anti-pattern.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LeakyService {

    private final DataSource dataSource;

    public final List<Connection> leakedConnections = new ArrayList<>();
    public final AtomicInteger acquired = new AtomicInteger();
    public final AtomicInteger leaked = new AtomicInteger();

    public Connection acquireWithoutClose() throws Exception {
        Connection conn = dataSource.getConnection();
        acquired.incrementAndGet();
        synchronized (leakedConnections) {
            leakedConnections.add(conn);
        }
        leaked.incrementAndGet();
        return conn;
    }

    public void cleanup() {
        synchronized (leakedConnections) {
            for (Connection c : leakedConnections) {
                try {
                    if (!c.isClosed()) c.close();
                } catch (Exception ignored) {
                }
            }
            leakedConnections.clear();
        }
    }
}
