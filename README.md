# Redis Session & Cache Demo (Spring Boot + AWS ElastiCache)

A runnable Spring Boot project showing three things people usually ask for together:

1. **User login sessions stored in Redis** (AWS ElastiCache) instead of server memory.
2. **Lazy Loading / Cache-Aside** pattern for reading user profiles.
3. **Write-Through** pattern for updating user profiles.

The database (H2 in this demo, RDS/Aurora in real life) is the system of record.
Redis/ElastiCache never becomes a second source of truth — it's a fast, disposable
copy of certain data, or the sole store for ephemeral data like sessions.

## Project layout

```
src/main/java/com/example/rediscache/
  config/RedisConfig.java        <- ElastiCache connection + RedisTemplate wiring
  config/WebConfig.java          <- registers the session auth filter
  config/DataInitializer.java    <- seeds 2 demo users on boot
  entity/User.java               <- JPA entity (DB row of truth)
  repository/UserRepository.java
  dto/                           <- request/response + the cached profile shape
  model/UserSession.java         <- object actually stored in Redis for a login
  service/AuthService.java       <- verifies credentials, creates sessions
  service/SessionService.java    <- Redis session CRUD (create/get/refresh/invalidate)
  service/UserCacheService.java  <- *** the Lazy Loading + Write-Through logic ***
  filter/SessionAuthFilter.java  <- looks up session token in Redis on every request
  controller/AuthController.java
  controller/UserController.java
```

## The two caching patterns, side by side

Both live in `UserCacheService`, operating on the same cached object (`user:profile:{id}`),
so you can compare them directly.

### Lazy Loading / Cache-Aside — `getUserProfile(id)`
```
GET /api/users/1
  -> check Redis for "user:profile:1"
  -> HIT:  return cached value, no DB call
  -> MISS: query the database, store result in Redis with a TTL, return it
```
Cache only gets populated *on demand*, the first time something is actually read.
Simple and self-healing, but the first read after a miss is slower ("cold" read).

### Write-Through — `updateUserProfile(id, ...)`
```
PUT /api/users/1
  -> write the change to the database (source of truth)
  -> immediately write the same change to Redis, in the same request
```
The cache is kept in lock-step with every write, so reads are never stale —
at the cost of every write touching two systems instead of one.

### Session storage (a third, related pattern)
```
POST /api/auth/login
  -> verify username/password against the DB
  -> create a random session token, store {userId, username, loginAt} in Redis
     under "session:{token}" with a TTL (app.session.ttl-seconds)
  -> return the token to the client
```
Every subsequent request sends `Authorization: Bearer {token}`. `SessionAuthFilter`
looks the token up straight from Redis on every request (no server-side memory,
so any instance behind a load balancer can serve any request) and slides the TTL
forward while the user stays active.

### Rate limiting (a fourth pattern)
Two independent limiters, both built on `RateLimiterService` (Redis `INCR` + `EXPIRE`,
the "fixed window" algorithm):

- **Login brute-force protection** — `AuthService.login()` limits attempts per
  *username* (`ratelimit:login:{username}`, default 10 attempts / 5 minutes). A
  successful login clears the counter immediately so a legitimate user isn't left
  mid-lockout after mistyping their password once or twice. Exceeding it returns
  `429` with a `Retry-After` header.
- **General API throttle** — `ApiRateLimitFilter` limits every `/api/*` request per
  *authenticated user* (falling back to client IP if anonymous), default 100
  requests / 60s (`ratelimit:api:{userId or ip}`). Every response carries
  `X-RateLimit-Limit` / `X-RateLimit-Remaining` headers; exceeding it returns `429`.

Both are fully configurable via `app.ratelimit.*` in `application.properties`.

## Running locally

You need a local Redis for quick testing (ElastiCache config comes later):

```bash
docker run -p 6379:6379 redis:7
mvn spring-boot:run
```

The app seeds two users: `alice` / `bob`, both with password `password123`.

### Try it with curl

```bash
# 1. Login -> get a session token
curl -s -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"alice","password":"password123"}'
# => {"sessionToken":"...", "expiresInSeconds":1800, "message":"Login successful"}

TOKEN=<paste sessionToken here>

# 2. Lazy-loaded read - first call is a DB hit + cache populate, watch the logs
curl -s http://localhost:8080/api/users/1 -H "Authorization: Bearer $TOKEN"

# 3. Same read again - now served entirely from Redis (check logs: "CACHE HIT")
curl -s http://localhost:8080/api/users/1 -H "Authorization: Bearer $TOKEN"

# 4. Write-through update - updates DB and Redis together
curl -s -X PUT http://localhost:8080/api/users/1 \
  -H "Content-Type: application/json" -H "Authorization: Bearer $TOKEN" \
  -d '{"email":"alice.new@example.com","fullName":"Alice N. Johnson"}'

# 5. Read again immediately - guaranteed fresh, no stale window (still a CACHE HIT in logs,
#    but with the *new* data because write-through already updated Redis)
curl -s http://localhost:8080/api/users/1 -H "Authorization: Bearer $TOKEN"

# 6. Logout
curl -s -X POST http://localhost:8080/api/auth/logout -H "Authorization: Bearer $TOKEN"
```

### Try the rate limiter

```bash
# Fire 11 bad login attempts back-to-back for the same username (limit is 10/5min)
for i in $(seq 1 11); do
  curl -s -o /dev/null -w "%{http_code}\n" -X POST http://localhost:8080/api/auth/login \
    -H "Content-Type: application/json" \
    -d '{"username":"alice","password":"wrong-password"}'
done
# First 10 -> 401 (invalid credentials), 11th -> 429 (rate limited) with Retry-After header

# Watch the general API throttle headers on any authenticated call
curl -si http://localhost:8080/api/users/1 -H "Authorization: Bearer $TOKEN" | grep -i ratelimit
```

Inspect Redis directly if you like:
```bash
docker exec -it <container> redis-cli
> KEYS *
> GET user:profile:1
> TTL session:<token>
```

## Pointing this at real AWS ElastiCache

1. **Create the cluster** (ElastiCache console or IaC): Redis engine, choose
   *Cluster Mode Disabled* for this project as written (a single primary + replicas
   is enough for session storage / small caches). Enable:
   - **Encryption in transit** (TLS) — required for anything holding session data.
   - **Encryption at rest**.
   - **Redis AUTH** (a password) if you're not using an in-VPC-only trust boundary.
2. **Networking**: put the cluster in the same VPC/subnets as your app, and add an
   inbound rule on the cluster's security group allowing port 6379 from your app's
   security group (this is the most common real-world connection failure).
3. **Set environment variables** for the app (matches `application.yml` placeholders):

   ```bash
   export REDIS_HOST=my-cache.abcdef.ng.0001.use1.cache.amazonaws.com   # primary endpoint
   export REDIS_PORT=6379
   export REDIS_SSL_ENABLED=true
   export REDIS_PASSWORD=<your-auth-token>
   ```

4. **Cluster Mode Enabled** (sharded): if you outgrow a single shard, switch
   `RedisConfig` to build a `RedisClusterConfiguration` from the *configuration
   endpoint* instead of `RedisStandaloneConfiguration`, and add
   `spring.data.redis.cluster.nodes` to your config.

## Notes on production hardening (not implemented here, on purpose, to keep the demo focused)

- Rotate session tokens on privilege changes, not just on login.
- Consider `spring-session-data-redis` if you want framework-managed `HttpSession`
  semantics instead of the hand-rolled filter here (the filter was written explicitly
  so the Redis read/write is visible and easy to follow).
- Add cache stampede protection (e.g. request coalescing or a short-lived lock) if
  a very hot key can expire under heavy concurrent read load.
- Never cache the password hash or other secrets — this project deliberately caches
  `UserProfileDTO`, not the `User` entity, for that reason.
