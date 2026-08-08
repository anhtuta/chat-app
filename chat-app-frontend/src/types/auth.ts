export interface AuthState {
  checking: boolean;
  isAuth: boolean;
  username: string | null;
  fullname: string | null;
}

export interface AuthCheckResponse {
  authenticated: boolean;
  username: string | null;
  fullname?: string | null;
}

export interface LoginResponse {
  success: boolean;
  username?: string | null;
  fullname?: string | null;
  message?: string;
}

export interface RegisterResponse {
  success: boolean;
  username?: string | null;
  fullname?: string | null;
  message?: string;
}
