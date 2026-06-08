/** Seed credentials — same defaults as bot-simulator (UserSeeder / GroupSeeder). */
export const SEED_USER = {
  username: process.env.E2E_USERNAME ?? "u1",
  password: process.env.E2E_PASSWORD ?? "5555",
};

export const SEED_GROUP_NAME = process.env.E2E_GROUP_NAME ?? "Group 1";
