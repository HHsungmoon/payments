/**
 * Mock PG — Authorize / Capture / Void / Query
 *
 * 환경변수:
 *   PORT          (default 9090)
 *   LATENCY_MS    응답 지연 (default 100)
 *   FAIL_RATE     실패율 0~1 (default 0)        — Circuit Breaker 시험용
 *   TIMEOUT_RATE  무응답율 0~1 (default 0)      — Timeout 시험용
 *
 * 엔드포인트:
 *   POST /pg/authorize         { idempotencyKey, method, amount } → { authId, status }
 *   POST /pg/capture           { authId, idempotencyKey }         → { txId, status }
 *   POST /pg/void              { authId, idempotencyKey }         → { status }
 *   GET  /pg/transactions/:key                                    → { status, authId }   (시나리오 ⑥ 복구용)
 *   GET  /healthz                                                 → "OK"
 */

import express from 'express';

const app = express();
app.use(express.json({ limit: '256kb' }));

const PORT         = parseInt(process.env.PORT || '9090', 10);
const LATENCY_MS   = parseInt(process.env.LATENCY_MS || '100', 10);
const FAIL_RATE    = parseFloat(process.env.FAIL_RATE || '0');
const TIMEOUT_RATE = parseFloat(process.env.TIMEOUT_RATE || '0');

// 멱등 캐시: idempotencyKey → { status, body }
const idempotencyCache = new Map();
// 트랜잭션 상태: authId → { status, method, amount, authIdemKey }
const transactions     = new Map();
// idempotencyKey(authorize) → authId 역인덱스 (시나리오 ⑥)
const idemToAuth       = new Map();

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const generateId = (prefix) =>
  `${prefix}_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`;

// 지연 + 옵션 장애 주입
async function maybeFault() {
  await sleep(LATENCY_MS);
  if (TIMEOUT_RATE > 0 && Math.random() < TIMEOUT_RATE) {
    // 영구 무응답 (Resilience4j Timeout 시험)
    await sleep(60_000);
  }
  if (FAIL_RATE > 0 && Math.random() < FAIL_RATE) {
    throw new Error('Simulated PG transient failure');
  }
}

// ── POST /pg/authorize ─────────────────────────────────
app.post('/pg/authorize', async (req, res) => {
  const { idempotencyKey, method, amount } = req.body || {};
  if (!idempotencyKey || !method || amount == null) {
    return res.status(400).json({ error: 'idempotencyKey, method, amount required' });
  }

  // 멱등 재생
  if (idempotencyCache.has(idempotencyKey)) {
    const c = idempotencyCache.get(idempotencyKey);
    return res.status(c.status).json(c.body);
  }

  try {
    await maybeFault();
  } catch (e) {
    return res.status(502).json({ error: e.message });
  }

  const authId = generateId('auth');
  transactions.set(authId, {
    status: 'AUTHORIZED',
    method,
    amount,
    authIdemKey: idempotencyKey,
  });
  idemToAuth.set(idempotencyKey, authId);

  const body = { authId, status: 'AUTHORIZED' };
  idempotencyCache.set(idempotencyKey, { status: 200, body });
  res.json(body);
});

// ── POST /pg/capture ───────────────────────────────────
app.post('/pg/capture', async (req, res) => {
  const { authId, idempotencyKey } = req.body || {};
  if (!authId) {
    return res.status(400).json({ error: 'authId required' });
  }

  const cacheKey = `cap:${idempotencyKey || authId}`;
  if (idempotencyCache.has(cacheKey)) {
    const c = idempotencyCache.get(cacheKey);
    return res.status(c.status).json(c.body);
  }

  const tx = transactions.get(authId);
  if (!tx)                  return res.status(404).json({ error: 'auth not found' });
  if (tx.status === 'VOIDED')   return res.status(409).json({ error: 'auth was voided' });
  if (tx.status === 'CAPTURED') return res.json({ txId: `tx_${authId}`, status: 'CAPTURED' });

  try {
    await maybeFault();
  } catch (e) {
    return res.status(502).json({ error: e.message });
  }

  tx.status = 'CAPTURED';
  const body = { txId: `tx_${authId}`, status: 'CAPTURED' };
  idempotencyCache.set(cacheKey, { status: 200, body });
  res.json(body);
});

// ── POST /pg/void ──────────────────────────────────────
app.post('/pg/void', async (req, res) => {
  const { authId, idempotencyKey } = req.body || {};
  if (!authId) {
    return res.status(400).json({ error: 'authId required' });
  }

  const cacheKey = `void:${idempotencyKey || authId}`;
  if (idempotencyCache.has(cacheKey)) {
    const c = idempotencyCache.get(cacheKey);
    return res.status(c.status).json(c.body);
  }

  const tx = transactions.get(authId);
  if (!tx)                     return res.status(404).json({ error: 'auth not found' });
  if (tx.status === 'CAPTURED') return res.status(409).json({ error: 'cannot void captured; use refund' });
  if (tx.status === 'VOIDED')   return res.json({ status: 'VOIDED' });

  try {
    await maybeFault();
  } catch (e) {
    return res.status(502).json({ error: e.message });
  }

  tx.status = 'VOIDED';
  const body = { status: 'VOIDED' };
  idempotencyCache.set(cacheKey, { status: 200, body });
  res.json(body);
});

// ── GET /pg/transactions/:key (시나리오 ⑥ 복구용) ───────
// key = pg_idempotency_key (authorize 시 사용한 키)
app.get('/pg/transactions/:key', (req, res) => {
  const authId = idemToAuth.get(req.params.key);
  if (!authId) return res.json({ status: 'NOT_FOUND' });

  const tx = transactions.get(authId);
  if (!tx)     return res.json({ status: 'NOT_FOUND' });

  res.json({ status: tx.status, authId });
});

// ── 헬스체크 ─────────────────────────────────────────
app.get('/healthz', (_req, res) => {
  res.type('text/plain').send('OK');
});

// 잡힌 적 없는 에러를 잡아 502로 변환 (Node 프로세스 안 죽이기)
app.use((err, _req, res, _next) => {
  console.error('[mock-pg] unexpected:', err);
  res.status(502).json({ error: 'mock-pg internal error' });
});

app.listen(PORT, () => {
  console.log(
    `[mock-pg] listening on :${PORT}  latency=${LATENCY_MS}ms  fail=${FAIL_RATE}  timeout=${TIMEOUT_RATE}`
  );
});
