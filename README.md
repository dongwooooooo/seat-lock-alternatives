# seat-lock-alternatives

[ticketing](https://github.com/dongwooooooo/ticketing) §5 (좌석 동시 선점 차단)에서 검토한 6가지 대안을 실제 코드 + 측정으로 입증한 레포.

## 실험 시나리오 (공통)

- 좌석 100번에 동시 100건의 예매 요청
- `ExecutorService(100)` + `CountDownLatch` 시작 게이트
- 합격선: 정확히 1건만 통과 (heldCount = 1, oversell 없음)
- 측정: success/rejected 수, 합격선 충족 여부, 관찰된 단점/장점

## 대안 목록

| 디렉터리 | 대안 | 채택? |
|---|---|---|
| [alt-a-partial-unique](alt-a-partial-unique/) | A. partial UNIQUE 인덱스 단독 | 기각 |
| [alt-b-optimistic-lock](alt-b-optimistic-lock/) | B. 낙관적 락 (@Version) | 기각 |
| [alt-c-insert-on-conflict](alt-c-insert-on-conflict/) | C. INSERT ON CONFLICT DO NOTHING | 기각 |
| [alt-d-redis-setnx](alt-d-redis-setnx/) | D. Redis SETNX 분산 락 | 기각 (4단계 이월) |
| [alt-e-skip-locked](alt-e-skip-locked/) | E. SELECT FOR UPDATE SKIP LOCKED | 기각 (시나리오 mismatch) |
| [alt-f-single-writer-queue](alt-f-single-writer-queue/) | F. 단일 작성자 큐 | 기각 (3단계 이월) |
| [alt-z-baseline](alt-z-baseline/) | Z. 비관적 락 + partial UNIQUE (2단 방어) | **채택** |

각 대안 디렉터리의 README에 상세 측정 결과 + 스크린샷 포함.

## 실행

```bash
./gradlew :alt-a-partial-unique:test
./gradlew :alt-b-optimistic-lock:test
# ...각 alt 동일
./gradlew test  # 전체
```

## 종합 비교 (각 대안 측정 결과 종합 예정)

각 alt 디렉터리의 README가 완료되면 본 표를 채운다.
