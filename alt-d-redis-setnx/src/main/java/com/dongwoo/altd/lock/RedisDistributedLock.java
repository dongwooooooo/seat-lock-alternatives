package com.dongwoo.altd.lock;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * 대안 D — Redis SETNX 기반 분산 락.
 *
 * 동작:
 *  - tryLock: SET key token NX EX ttl → 단일 원자 명령. 이미 키가 있으면 null 반환 → false.
 *  - unlock: GET 후 token이 동일하면 DEL. 이 두 명령은 Lua로 묶어 원자 보장.
 *
 * 한계 (Kleppmann, "How to do distributed locking"):
 *  - TTL이 만료된 뒤 원래 소유자가 GC pause에서 깨어나 unlock을 시도하면, 그 사이
 *    다른 클라이언트가 같은 키로 락을 잡고 있을 수 있다 → token 비교로 잘못된 해제는 막을 수 있다.
 *  - 그러나 GC pause 도중 만료된 락의 원 소유자가 DB write를 계속 진행하면, 동시에
 *    새 락 소유자도 DB write를 진행한다 → fencing token 없이는 막을 수 없다.
 *  - 본 구현은 fencing token 없음. 단일 노드 + 짧은 critical section + Redis 단일 인스턴스 환경에서만 안전.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisDistributedLock {

    private static final String UNLOCK_LUA =
            "if redis.call('get', KEYS[1]) == ARGV[1] then " +
            "  return redis.call('del', KEYS[1]) " +
            "else " +
            "  return 0 " +
            "end";

    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT =
            new DefaultRedisScript<>(UNLOCK_LUA, Long.class);

    private final StringRedisTemplate redis;

    /**
     * SET key value NX EX ttlSeconds.
     * @return true 면 락 획득, false 면 이미 누군가가 보유 중.
     */
    public boolean tryLock(String key, String token, int ttlSeconds) {
        Boolean ok = redis.opsForValue().setIfAbsent(key, token, Duration.ofSeconds(ttlSeconds));
        return Boolean.TRUE.equals(ok);
    }

    /**
     * Lua 스크립트로 token이 일치할 때만 DEL.
     * @return true 면 본인이 잡고 있던 락을 정확히 해제. false 면 이미 만료되었거나 다른 소유자가 잡고 있음.
     */
    public boolean unlock(String key, String token) {
        Long result = redis.execute(UNLOCK_SCRIPT, List.of(key), token);
        return result != null && result == 1L;
    }

    /** 디버그/테스트용 — 키 현재 값을 직접 조회. */
    public String peek(String key) {
        return redis.opsForValue().get(key);
    }
}
