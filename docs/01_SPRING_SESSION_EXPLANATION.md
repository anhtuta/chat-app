# Spring Session Creation Explained

Tóm tắt:

- User login
- Spring tạo 1 session mới (lưu Redis qua Spring Session), sau đó set cookie session (`CHATAPP_SESSION`) cho browser
- FE dùng cookie đó để gọi HTTP API; mỗi request HTTP “chạm” session sẽ **trượt (slide)** lại TTL Redis theo `server.servlet.session.timeout` (inactivity), xem mục sliding renewal
- Cookie HTTP **không** gắn vào từng frame WebSocket sau khi đã handshake: xoá cookie trên DevTools vẫn có thể gửi message trên socket đang mở — gap logout/WS xem [`18_LOGOUT_WEBSOCKET_SESSION_LIFECYCLE.md`](./18_LOGOUT_WEBSOCKET_SESSION_LIFECYCLE.md)

## How Spring Creates Sessions

### 1. **When is a Session Created?**

A session is created automatically by the servlet container (Tomcat, in Spring Boot's case) when:

- A request comes in and `HttpSession` is accessed for the first time
- OR when Spring Security needs to store authentication information

In your code, the session is created when you call:

```java
authenticateUser(user, session);  // session parameter triggers session creation
```

### 2. **Session Cookie Name**

**What cookie name is used here?**

- `JSESSIONID` is the classic cookie name used by Java servlet containers (like Tomcat)
- `SESSION` is the typical default when using Spring Session
- This app explicitly configures the cookie name as `CHATAPP_SESSION`
- The cookie value is still just the **session identifier** that links the browser to the server-side session

**How is it created?**

1. When a session is first created on the server, Tomcat generates a unique session ID
2. Spring Session writes a `Set-Cookie` header to the HTTP response using the configured cookie name:
   ```
   Set-Cookie: CHATAPP_SESSION=ABC123XYZ456; Path=/; HttpOnly
   ```
3. The cookie contains the session ID that uniquely identifies this session

**When is it created?**

- Created automatically when `HttpSession` is first accessed
- Happens in your code when `authenticateUser()` stores data in the session

### 3. **How Browser Stores the Cookie**

**Automatic Process:**

1. Browser receives the `Set-Cookie` header in the HTTP response
2. Browser automatically stores the cookie in its cookie storage
3. Cookie is associated with your domain (e.g., `localhost:8080`)
4. Browser automatically sends the cookie back in subsequent requests via `Cookie` header:
   ```
   Cookie: CHATAPP_SESSION=ABC123XYZ456
   ```

**Cookie Properties:**

- **Path**: `/` - cookie is sent for all paths on the domain
- **HttpOnly**: `true` - cookie cannot be accessed via JavaScript (security feature)
- **Secure**: `false` (unless using HTTPS) - cookie sent over HTTP/HTTPS
- **SameSite**: Browser default - prevents CSRF attacks

### 4. **Session Storage**

**Server-side:**

- Session data is stored in server memory (by default)
- Key-value pairs stored in `HttpSession` object
- In your code, you store:
  - `SecurityContext` (Spring Security authentication info)
  - `user` (User entity)
  - `username` (String)

**Client-side:**

- Only the `CHATAPP_SESSION` cookie is stored in the browser
- No actual session data is stored client-side (for security)
- Cookie is typically stored in browser's cookie storage

### 5. **Session Lifecycle**

```
1. User logs in
   ↓
2. authenticateUser() is called
   ↓
3. HttpSession is accessed → Session created on server
   ↓
4. Spring Session generates or reuses a unique session ID
   ↓
5. `CHATAPP_SESSION` cookie sent to browser in response
   ↓
6. Browser stores cookie
   ↓
7. Subsequent requests include `CHATAPP_SESSION` cookie
   ↓
8. Server looks up session using session ID
   ↓
9. Session data retrieved (authentication, user info, etc.)
```

### 6. **In Your Code**

When `login()` is called:

```java
@PostMapping("/login")
public ResponseEntity<AuthResponse> login(@Valid @RequestBody UserRequest request, HttpSession session) {
    // ...
    authenticateUser(user, session);  // ← Session created here!
    // ...
}
```

Inside `authenticateUser()`:

```java
session.setAttribute(...);  // ← First access to session → Session created!
```

**What happens:**

1. `session.setAttribute()` is called
2. If session doesn't exist, Tomcat creates it
3. Session ID generated (e.g., "ABC123XYZ456")
4. `Set-Cookie: CHATAPP_SESSION=ABC123XYZ456` added to response
5. Browser receives and stores the cookie
6. Future requests include this cookie automatically

### 7. **Session Configuration**

In your `SecurityConfig`:

```java
.sessionManagement(session -> session
    .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
)
```

**SessionCreationPolicy.IF_REQUIRED** means:

- Session is created only if needed (when you access `HttpSession`)
- This is the default and most common setting

### 8. **Important Notes**

- **Session is server-side**: All session data lives on the server (in this app: **Redis** via Spring Session, namespace `chatapp`)
- **Cookie is just an identifier**: The session cookie only contains the session ID, not the actual data. With Spring Session Redis the default name is typically `SESSION`, and this app overrides it to `CHATAPP_SESSION`
- **Automatic**: You don't manually create cookies - Spring Session / the servlet container handles it
- **Secure by default**: Cookies are HttpOnly (not accessible via JavaScript)
- **Session timeout**: Configured as **inactivity** timeout (see sliding renewal below), not an absolute max login age

### 9. **Sliding renewal (why Redis TTL keeps resetting)**

Configured in `application.yaml`:

```yaml
server:
  servlet:
    session:
      cookie:
        name: CHATAPP_SESSION
      timeout: 43200 # seconds of inactivity (e.g. half day)
```

#### What `timeout` means

- It is **max inactive interval**, not “force logout N seconds after login.”
- Clock starts from the session’s **last accessed time**.
- If the user is idle longer than `timeout` with **no HTTP request that loads the session**, Redis deletes the session key and the next HTTP call is unauthenticated.
- There is **no refresh token** and no silent re-login. Auth stays session-based; the same opaque cookie keeps working only while the Redis session still exists.
- Tức là: khoảng thời gian nửa ngày trên nghĩa là nếu user không làm gì trong nửa ngày thì session sẽ hết và user sẽ phải đăng nhập lại. Còn nếu user liên tục tương tác với app thì session sẽ không hết.

#### Why the Redis key’s TTL jumps back (e.g. to 2 minutes again)

Giả sử set `server.servlet.session.timeout=120s` (2 phút).

With Spring Session’s default Redis repository (`RedisSessionRepository`):

1. Browser sends the session cookie on an HTTP request (`/api/**`, SockJS `/ws` handshake/reconnect, etc.).
2. Spring Session loads `chatapp:sessions:<id>` from Redis.
3. The request updates `lastAccessedTime` on the session.
4. At the end of the request (`flush-mode: on_save`), Spring Session writes the delta and sets Redis expiry roughly to:

   `lastAccessedTime + maxInactiveInterval`

5. Redis TTL therefore looks “renewed” (e.g. countdown 32 → suddenly ~120 again). The **same session id** is kept; the key was not deleted and recreated.

Observed pattern when watching Redis while the chat tab is open:

```
TTL=32
TTL=113   ← some HTTP traffic touched the session; sliding renewal, not a new login
```

#### What renews TTL vs what does not

| Renews Redis session TTL? | Traffic                                                                                                                                         |
| ------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------- |
| Yes                       | REST calls with the session cookie (`credentials: "include"`)                                                                                   |
| Yes                       | `/api/auth/check` on app load (loads SecurityContext from session)                                                                              |
| Yes                       | SockJS/WebSocket **HTTP handshake or reconnect** to `/ws/**`                                                                                    |
| No                        | STOMP heartbeats / frames on an **already upgraded** WebSocket (identity lives in WS session attrs; see [`16_AUTH_FLOW.md`](./16_AUTH_FLOW.md)) |

So: an idle browser with no HTTP session access will expire; an open tab that keeps making session-backed HTTP calls (or reconnecting SockJS) will keep sliding the TTL and can stay logged in for days.

#### How to verify true expiry

1. Note the session UUID under `chatapp:sessions:<id>`.
2. Close all app tabs (no SockJS reconnect / API traffic).
3. Wait longer than `timeout` → key should disappear and **stay** gone.
4. If TTL resets and the UUID is unchanged, that was sliding renewal, not resurrection.

Related gap (logout vs WebSocket still alive): planned in [`18_LOGOUT_WEBSOCKET_SESSION_LIFECYCLE.md`](./18_LOGOUT_WEBSOCKET_SESSION_LIFECYCLE.md).

### 10. **Session vs Cookie**

| Aspect       | Session                          | Cookie                      |
| ------------ | -------------------------------- | --------------------------- |
| **Storage**  | Server memory                    | Browser storage             |
| **Data**     | User data, auth info             | Only session ID             |
| **Security** | More secure                      | Less secure (can be stolen) |
| **Size**     | Unlimited (within server limits) | Limited (4KB)               |
| **Lifetime** | Until timeout/logout             | Until expiration            |

**Summary**: The session stores your data on the server, and the `CHATAPP_SESSION` cookie is just a key to find that data.

# How Spring Security uses `SPRING_SECURITY_CONTEXT_KEY`:

- We store it in `authenticateUser()`:

```java
session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, securityContext);
```

- Spring Security retrieves it automatically on each HTTP request via its filter chain (`HttpSessionSecurityContextRepository`), loads it into `SecurityContextHolder`, and makes it available via `SecurityContextHolder.getContext()`.

- We use it in:
  1. `checkAuth()` endpoint — reads from `SecurityContextHolder.getContext().getAuthentication()`
  2. Spring Security authorization — `.authenticated()` checks use the `SecurityContext`
  3. `WebSocketHandshakeInterceptor` — fallback when the user object isn't in session

We need it because:

1. Spring Security's authorization checks (`.authenticated()` in `SecurityConfig`) rely on the SecurityContext being in the session.
2. The `/check` endpoint uses `SecurityContextHolder.getContext().getAuthentication()`.
3. It's the standard Spring Security pattern for session-based authentication.

We could simplify by removing it and using only the user object, but that would require:

- Changing `checkAuth()` to read from the session instead of `SecurityContextHolder`
- Ensuring Spring Security's `.authenticated()` checks still work (they may not without a SecurityContext)
