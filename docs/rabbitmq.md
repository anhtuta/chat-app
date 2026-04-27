## Why we typically don't delete exchanges on shutdown

1. Multi-instance safety: In a multi-instance setup, one instance shutting down shouldn't delete exchanges that other instances may still use.
2. Lightweight: Exchanges are metadata and consume minimal resources.
3. Idempotent creation: `declareExchange` is idempotent, so recreating on startup is safe.
4. Persistence: Durable exchanges persist across broker restarts, which is often desired.

### Recommendation

**For production: don't delete exchanges** (current approach is fine).

**For development/testing:** If you want cleanup, you can track exchanges, but be cautious in multi-instance setups.

Keep the current approach (no exchange cleanup):

1. Exchanges persist and are reused
2. Safe for multi-instance
3. **Manual cleanup via RabbitMQ Management UI when needed**
4. Exchanges are lightweight and don't cause issues
