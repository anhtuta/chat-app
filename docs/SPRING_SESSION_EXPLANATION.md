# Spring Session Creation Explained

Tóm tắt:

- User login
- Spring tạo 1 session mới, sau đó set cookie `JSESSIONID` cho browser
- FE dùng cookie đó đề truy cập trang chat và fetch all messages
- Nhưng cookie đó KHÔNG dùng cho websocket connection, tức là nếu login xong, rồi xoá cookie = devtool thì vẫn send được message, vì cookie đó dùng cho http request, còn send message dùng websocket

## How Spring Creates Sessions

### 1. **When is a Session Created?**

A session is created automatically by the servlet container (Tomcat, in Spring Boot's case) when:

- A request comes in and `HttpSession` is accessed for the first time
- OR when Spring Security needs to store authentication information

In your code, the session is created when you call:

```java
authenticateUser(user, session);  // session parameter triggers session creation
```

### 2. **JSESSIONID Cookie**

**What is JSESSIONID?**

- `JSESSIONID` is a cookie name used by Java servlet containers (like Tomcat)
- It's the **session identifier** that links the browser to the server-side session

**How is it created?**

1. When a session is first created on the server, Tomcat generates a unique session ID
2. Tomcat automatically adds a `Set-Cookie` header to the HTTP response:
   ```
   Set-Cookie: JSESSIONID=ABC123XYZ456; Path=/; HttpOnly
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
   Cookie: JSESSIONID=ABC123XYZ456
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

- Only the `JSESSIONID` cookie is stored in the browser
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
4. Tomcat generates unique session ID
   ↓
5. JSESSIONID cookie sent to browser in response
   ↓
6. Browser stores cookie
   ↓
7. Subsequent requests include JSESSIONID cookie
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
4. `Set-Cookie: JSESSIONID=ABC123XYZ456` added to response
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

- **Session is server-side**: All session data lives on the server
- **Cookie is just an identifier**: The `JSESSIONID` cookie only contains the session ID, not the actual data
- **Automatic**: You don't manually create cookies - Tomcat/Spring handles it
- **Secure by default**: Cookies are HttpOnly (not accessible via JavaScript)
- **Session timeout**: Default is 30 minutes of inactivity (configurable)

### 9. **Session vs Cookie**

| Aspect       | Session                          | Cookie                      |
| ------------ | -------------------------------- | --------------------------- |
| **Storage**  | Server memory                    | Browser storage             |
| **Data**     | User data, auth info             | Only session ID             |
| **Security** | More secure                      | Less secure (can be stolen) |
| **Size**     | Unlimited (within server limits) | Limited (4KB)               |
| **Lifetime** | Until timeout/logout             | Until expiration            |

**Summary**: The session stores your data on the server, and the `JSESSIONID` cookie is just a key to find that data.
