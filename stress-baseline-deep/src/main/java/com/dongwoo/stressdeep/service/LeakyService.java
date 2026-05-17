package com.dongwoo.stressdeep.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 시나리오 6 — Connection leak detection.
 *
 * 정상 코드라면 DataSource.getConnection() 후 try-with-resources / close() 호출 필수.
 * 본 서비스는 의도적으로 close 누락하는 anti-pattern 을 시뮬레이션.
 *
 * HikariCP leakDetectionThreshold=5000ms 설정 → close 안 한 connection 이 5초 후 경고 로그 발생.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LeakyService {

    private final DataSource dataSource;

    public final List<Connection> leakedConnections = new ArrayList<>();
    public final AtomicInteger acquired = new AtomicInteger();
    public final AtomicInteger leaked = new AtomicInteger();

    /**
     * close 하지 않은 채 connection 반환. 호출자가 모아서 leaked list에 보관.
     * 테스트 종료 시 cleanup() 로 모두 close.
     */
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
