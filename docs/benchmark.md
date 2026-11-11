# Latency benchmark

`LatencyBenchmarkTest` measures **end-to-end delivery latency**: the time from the moment the
sender submits a message until a *different* user's client holds it. It covers three designs,
against the same server and database:

1. **STOMP push.** The sender SENDs over an open WebSocket and the receiver's subscription gets the
   broadcast.
2. **HTTP request/response, best case.** The sender POSTs, then the receiver immediately GETs the
   newest message. There is no polling delay at all, so this is the *floor* for any request/response
   design.
3. **HTTP short polling.** The receiver GETs every 1 s, which is how request/response chat apps
   usually work.

Both HTTP clients reuse keep-alive connections, so the comparison does not penalize HTTP for
connection setup.

## Results

Run on 2026-11-12: JDK 17.0.20, 8 cores, Windows 11, loopback network, in-memory H2.
There were 1,000 samples after a 200-sample warm-up; polling used 60 samples.

| Transport | p50 (ms) | p95 (ms) | mean (ms) |
|---|---:|---:|---:|
| STOMP over WebSocket (push) | **9.53** | 13.37 | 9.85 |
| HTTP request/response, best case (POST + immediate GET) | 18.45 | 24.23 | 18.91 |
| HTTP short polling every 1000 ms | 519.4 | 968.4 | 518.4 |

- WebSocket p50 is **48% lower than the best possible request/response round trip**. Push needs
  one network hop to the receiver, where request/response needs two full HTTP exchanges.
- Against realistic 1-second polling, push is **98% lower** (about 9.5 ms against about 520 ms).
  Polling latency is dominated by the interval: on average, half of it.

Loopback hides network round-trip time. On a real network each extra HTTP exchange adds a full RTT,
so the gap usually **widens**.

## Reproduce

```bash
./mvnw test -Pbenchmark          # about 2 minutes; prints the table
cat target/benchmark/latency.md  # same table, written to disk
```
