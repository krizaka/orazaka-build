package com.orazaka.test;

/**
 * Shared test literal constants used across multiple test modules.
 *
 * <p>Extracted to eliminate Sonar S1192 (duplicate string literals) violations across the monorepo.
 * Each constant is documented with the approximate cross-module usage count.
 *
 * <p>Usage: {@code import static com.orazaka.test.TestConstants.*;}
 */
public final class TestConstants {

  private TestConstants() {
    // utility class — no instantiation
  }

  // ─── Provider Names ───────────────────────────────────────────────────────
  /** Default AI provider identifier. */
  public static final String PROVIDER_OLLAMA = "ollama";

  /** OpenAI provider identifier. */
  public static final String PROVIDER_OPENAI = "openai";

  // ─── User & Session Identifiers ───────────────────────────────────────────
  /** Generic test user identifier (short form). */
  public static final String USER_1 = "user1";

  /** Generic test user identifier (dash form). */
  public static final String USER_DASH_1 = "user-1";

  /** Generic user literal. */
  public static final String USER = "user";

  /** Short user alias. */
  public static final String U1 = "u1";

  /** Generic admin identifier. */
  public static final String ADMIN = "admin";

  /** Generic conversation identifier. */
  public static final String CONV_1 = "conv-1";

  /** Generic session identifier. */
  public static final String SESSION_1 = "session-123";

  // ─── Prompt & Response Literals ───────────────────────────────────────────
  /** Default test prompt text (lowercase). */
  public static final String PROMPT_HELLO = "hello";

  /** Default test prompt text (capitalized). */
  public static final String PROMPT_HELLO_CAP = "Hello";

  /** Generic prompt literal. */
  public static final String PROMPT = "prompt";

  /** Generic query literal. */
  public static final String QUERY = "query";

  /** Generic response literal. */
  public static final String RESPONSE = "Response";

  /** Fuzzy prompt literal. */
  public static final String FUZZY_PROMPT = "Fuzzy prompt";

  // ─── Domain Constants ─────────────────────────────────────────────────────
  /** Generic test identifier. */
  public static final String TEST = "test";

  /** Chat category. */
  public static final String CHAT = "chat";

  /** Category literal. */
  public static final String CATEGORY = "category";

  /** Image literal. */
  public static final String IMAGE = "image";

  /** Generic key literal. */
  public static final String KEY = "key";

  /** Generic key1 literal. */
  public static final String KEY_1 = "key1";

  /** Generic name literal. */
  public static final String NAME = "name";

  /** Generic id literal. */
  public static final String ID = "id";

  // ─── Job & Status Constants ───────────────────────────────────────────────
  /** Generic job identifier. */
  public static final String JOB_1 = "job-1";

  /** Pending status. */
  public static final String STATUS_PENDING = "PENDING";

  /** Completed status. */
  public static final String STATUS_COMPLETED = "COMPLETED";

  // ─── HTTP ─────────────────────────────────────────────────────────────────
  /** POST method literal. */
  public static final String METHOD_POST = "POST";

  // ─── Security ─────────────────────────────────────────────────────────────
  /** Default role user. */
  public static final String ROLE_USER = "ROLE_USER";

  // ─── Model Constants ──────────────────────────────────────────────────────
  /** Model identifier short form. */
  public static final String MODEL_M1 = "m1";

  /** Tool name literal. */
  public static final String TOOL_1 = "tool1";

  /** Analyze poster tool name. */
  public static final String TOOL_ANALYZE_POSTER = "analyzePoster";

  /** GPT-4o model name. */
  public static final String MODEL_GPT4O = "gpt-4o";

  /** Test API key. */
  public static final String TEST_API_KEY = "sk-test-key";

  // ─── Architecture Package Patterns ────────────────────────────────────────
  /** Core module package pattern for ArchUnit. */
  public static final String PKG_CORE = "com.orazaka.core..";

  /** Identity module package pattern for ArchUnit. */
  public static final String PKG_IDENTITY = "com.orazaka.identity..";

  /** Router module package pattern for ArchUnit. */
  public static final String PKG_ROUTER = "com.orazaka.conversationservice..";

  /** Tools module package pattern for ArchUnit. */
  public static final String PKG_TOOLS = "com.orazaka.tools..";

  // ─── Auth/Identity ────────────────────────────────────────────────────────
  /** Test email. */
  public static final String TEST_EMAIL = "user@test.com";

  /** Short test email. */
  public static final String TEST_EMAIL_SHORT = "e@t.com";

  /** Test password. */
  public static final String TEST_PASSWORD = "password";

  /** Hashed password literal. */
  public static final String HASHED = "hashed";

  /** Hashed password literal (full). */
  public static final String HASHED_PASSWORD = "hashedPassword";

  /** Test language (English). */
  public static final String LANG_EN = "en";

  /** Test language (French). */
  public static final String LANG_FR = "fr";

  /** Test theme. */
  public static final String THEME = "theme";

  // ─── Misc ─────────────────────────────────────────────────────────────────
  /** Standard suppress warnings unchecked. */
  public static final String UNCHECKED = "unchecked";

  /** Label L1. */
  public static final String LABEL_L1 = "L1";

  /** Options literal. */
  public static final String OPTS = "opts";

  /** Short session alias. */
  public static final String S1 = "s1";

  /** Title literal. */
  public static final String TITLE = "title";

  /** URL literal. */
  public static final String URL = "url";

  /** Pass literal. */
  public static final String PASS = "pass";

  /** Feat literal. */
  public static final String FEAT = "feat";
}
