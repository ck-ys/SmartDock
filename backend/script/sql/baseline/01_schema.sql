-- SmartDock 基线 Schema: 71 张存活表 DDL
-- 前置要求: PostgreSQL 14+; 扩展 pg_trgm(祖先路径模糊检索)
CREATE EXTENSION IF NOT EXISTS pg_trgm;

--
-- PostgreSQL database dump
--


-- Dumped from database version 17.10 (Debian 17.10-1.pgdg13+1)
-- Dumped by pg_dump version 17.10

SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
SET transaction_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SELECT pg_catalog.set_config('search_path', '', false);
SET check_function_bodies = false;
SET xmloption = content;
SET client_min_messages = warning;
SET row_security = off;

SET default_tablespace = '';

SET default_table_access_method = heap;

--
-- Name: dcs_ai_config; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.dcs_ai_config (
    config_id bigint NOT NULL,
    tenant_id character varying(20) NOT NULL,
    provider character varying(32) NOT NULL,
    base_url character varying(255),
    api_key text,
    model_name character varying(128) NOT NULL,
    temperature numeric(4,2) DEFAULT 0.20,
    timeout_seconds integer DEFAULT 60,
    enabled character(1) DEFAULT '1'::bpchar,
    remark character varying(500),
    create_dept bigint,
    create_by bigint,
    create_time timestamp without time zone,
    update_by bigint,
    update_time timestamp without time zone,
    del_flag character(1) DEFAULT '0'::bpchar,
    context_window integer DEFAULT 128000,
    max_tokens integer,
    reasoning_effort character varying(16) DEFAULT ''::character varying,
    unit_price_per_call numeric(10,4) DEFAULT 0.0100,
    balance_query_enabled character(1) DEFAULT '1'::bpchar,
    billing_mode character varying(32) DEFAULT 'OFFICIAL_TOKEN'::character varying,
    service_fee_rate numeric(8,2) DEFAULT 0,
    use_type character varying(16) DEFAULT 'CHAT'::character varying NOT NULL,
    is_default character(1),
    credit_multiplier numeric(6,2) DEFAULT 1 NOT NULL,
    CONSTRAINT ck_dcs_ai_config_use_type CHECK (((use_type)::text = ANY (ARRAY[('CHAT'::character varying)::text, ('EMBEDDING'::character varying)::text])))
);


--
-- Name: dcs_ai_credit_ledger; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.dcs_ai_credit_ledger (
    id bigint NOT NULL,
    tenant_id character varying(20) NOT NULL,
    biz_type character varying(20) NOT NULL,
    credits integer NOT NULL,
    weekly_credits integer DEFAULT 0 NOT NULL,
    tank_credits integer DEFAULT 0 NOT NULL,
    scene character varying(120),
    idempotency_key character varying(120),
    remark character varying(300),
    create_by bigint,
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP
);


--
-- Name: dcs_ai_credit_plan; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.dcs_ai_credit_plan (
    id bigint NOT NULL,
    plan_code character varying(40) NOT NULL,
    kind character varying(20) DEFAULT 'SUBSCRIBE'::character varying NOT NULL,
    plan_name character varying(60) NOT NULL,
    price_yuan numeric(12,2) NOT NULL,
    weekly_credits integer DEFAULT 0 NOT NULL,
    window_credits integer DEFAULT 0 NOT NULL,
    window_hours integer DEFAULT 5 NOT NULL,
    duration_weeks integer DEFAULT 12 NOT NULL,
    description character varying(300),
    enabled character(1) DEFAULT '1'::bpchar NOT NULL,
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    websearch_limit_per_user_daily integer DEFAULT 0 NOT NULL,
    total_credits bigint
);


--
-- Name: dcs_ai_credit_subscription; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.dcs_ai_credit_subscription (
    id bigint NOT NULL,
    tenant_id character varying(20) NOT NULL,
    plan_id bigint NOT NULL,
    plan_code character varying(40) NOT NULL,
    plan_name character varying(60) NOT NULL,
    start_time timestamp without time zone NOT NULL,
    end_time timestamp without time zone NOT NULL,
    status character(1) DEFAULT '1'::bpchar NOT NULL,
    create_by bigint,
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP
);


--
-- Name: dcs_ai_doc_domain; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.dcs_ai_doc_domain (
    domain_id bigint NOT NULL,
    tenant_id character varying(20) DEFAULT '000000'::character varying NOT NULL,
    domain character varying(255) NOT NULL,
    provider character varying(64),
    remark character varying(500),
    enabled character(1) DEFAULT '1'::bpchar NOT NULL,
    create_dept bigint,
    create_by bigint,
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    update_by bigint,
    update_time timestamp without time zone,
    del_flag character(1) DEFAULT '0'::bpchar NOT NULL
);


--
-- Name: dcs_ai_model_capability; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.dcs_ai_model_capability (
    id bigint NOT NULL,
    tenant_id character varying(20) DEFAULT '000000'::character varying NOT NULL,
    provider character varying(64) NOT NULL,
    model_pattern character varying(200) NOT NULL,
    tier_kind character varying(10) NOT NULL,
    tier_values text,
    semantic_map text,
    context_window bigint,
    source character varying(10) DEFAULT 'SEED'::character varying NOT NULL,
    rule_order integer DEFAULT 100 NOT NULL,
    create_dept bigint,
    create_by bigint,
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    update_by bigint,
    update_time timestamp without time zone,
    del_flag character(1) DEFAULT '0'::bpchar,
    max_output_tokens bigint,
    supports_image boolean DEFAULT false NOT NULL,
    supports_video boolean DEFAULT false NOT NULL,
    supports_audio boolean DEFAULT false NOT NULL
);


--
-- Name: dcs_ai_model_health; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.dcs_ai_model_health (
    id bigint NOT NULL,
    tenant_id character varying(20) NOT NULL,
    config_id bigint NOT NULL,
    provider character varying(64),
    model_name character varying(128),
    status character varying(8) NOT NULL,
    latency_ms integer,
    detail character varying(500),
    fail_streak integer DEFAULT 0 NOT NULL,
    check_time timestamp without time zone NOT NULL,
    create_time timestamp without time zone
);


--
-- Name: dcs_ai_model_price; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.dcs_ai_model_price (
    price_id bigint NOT NULL,
    tenant_id character varying(20) DEFAULT '000000'::character varying NOT NULL,
    provider character varying(32) NOT NULL,
    model_name character varying(128) NOT NULL,
    currency character varying(16) DEFAULT 'USD'::character varying NOT NULL,
    input_price_per_million numeric(14,6) DEFAULT 0 NOT NULL,
    cached_input_price_per_million numeric(14,6) DEFAULT 0,
    output_price_per_million numeric(14,6) DEFAULT 0 NOT NULL,
    source_url character varying(500),
    enabled character(1) DEFAULT '1'::bpchar NOT NULL,
    remark character varying(500),
    create_dept bigint,
    create_by bigint,
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    update_by bigint,
    update_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    del_flag character(1) DEFAULT '0'::bpchar,
    pricing_region character varying(16),
    pricing_tier_code character varying(64),
    input_token_min integer,
    input_token_max integer,
    billing_formula character varying(64) DEFAULT 'INPUT_OUTPUT_PER_MILLION'::character varying,
    pricing_schema_json text,
    source_updated_at timestamp without time zone
);


--
-- Name: dcs_ai_provider_access; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.dcs_ai_provider_access (
    id bigint NOT NULL,
    tenant_id character varying(20) NOT NULL,
    provider character varying(32) NOT NULL,
    base_url character varying(255) NOT NULL,
    api_key text NOT NULL,
    console_url character varying(255),
    remark character varying(500),
    create_dept bigint,
    create_by bigint,
    create_time timestamp without time zone,
    update_by bigint,
    update_time timestamp without time zone,
    del_flag character(1) DEFAULT '0'::bpchar,
    label character varying(64),
    enabled character(1) DEFAULT '1'::bpchar NOT NULL,
    balance_amount numeric(14,4),
    balance_currency character varying(8),
    balance_checked_at timestamp without time zone,
    balance_status character varying(16)
);


--
-- Name: dcs_ai_usage; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.dcs_ai_usage (
    usage_id bigint NOT NULL,
    tenant_id character varying(20) NOT NULL,
    config_id bigint NOT NULL,
    provider character varying(32),
    model_name character varying(128),
    caller character varying(64),
    scene character varying(64),
    operator_id bigint,
    latency_ms integer,
    cost_amount numeric(14,8) DEFAULT 0 NOT NULL,
    unit_price numeric(10,4),
    prompt_tokens integer,
    completion_tokens integer,
    total_tokens integer,
    success character(1) DEFAULT '1'::bpchar,
    error_msg character varying(500),
    create_time timestamp without time zone DEFAULT now(),
    create_dept bigint,
    create_by bigint,
    update_by bigint,
    update_time timestamp without time zone,
    base_cost_amount numeric(14,8) DEFAULT 0,
    service_fee_rate numeric(8,2) DEFAULT 0,
    service_fee_amount numeric(14,8) DEFAULT 0,
    currency character varying(16) DEFAULT 'CNY'::character varying,
    billing_mode character varying(32) DEFAULT 'FIXED_PER_CALL'::character varying,
    pricing_snapshot text,
    cached_prompt_tokens integer,
    wallet_id bigint,
    wallet_ledger_id bigint
);


--
-- Name: dcs_ai_usage_usage_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.dcs_ai_usage ALTER COLUMN usage_id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.dcs_ai_usage_usage_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: dcs_assistant_action; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.dcs_assistant_action (
    id bigint NOT NULL,
    tenant_id character varying(20) NOT NULL,
    user_id bigint NOT NULL,
    run_id character varying(128) NOT NULL,
    thread_id character varying(128),
    tool_name character varying(64) NOT NULL,
    args_json text,
    status character varying(16) NOT NULL,
    result_summary character varying(500),
    idempotency_key character varying(128) NOT NULL,
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    update_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP
);


--
-- Name: dcs_assistant_announcement; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.dcs_assistant_announcement (
    id bigint NOT NULL,
    tenant_id character varying(20) NOT NULL,
    title character varying(120) NOT NULL,
    content character varying(1000),
    style character varying(16) DEFAULT 'NORMAL'::character varying NOT NULL,
    status character(1) DEFAULT '1'::bpchar NOT NULL,
    publish_by bigint,
    publish_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    update_time timestamp without time zone
);


--
-- Name: dcs_assistant_attachment; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.dcs_assistant_attachment (
    id bigint NOT NULL,
    tenant_id character varying(20) NOT NULL,
    thread_id character varying(128) NOT NULL,
    user_id bigint NOT NULL,
    oss_id bigint NOT NULL,
    file_name character varying(255) NOT NULL,
    file_size bigint NOT NULL,
    suffix character varying(16) NOT NULL,
    parse_status character varying(16) DEFAULT 'PENDING'::character varying NOT NULL,
    parse_error character varying(500),
    parsed_text text,
    line_count integer,
    create_dept bigint,
    create_by bigint,
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    update_by bigint,
    update_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    del_flag character(1) DEFAULT '0'::bpchar
);


--
-- Name: dcs_assistant_config; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.dcs_assistant_config (
    tenant_id character varying(20) NOT NULL,
    enabled character(1) DEFAULT '0'::bpchar NOT NULL,
    daily_round_limit_per_user integer DEFAULT 50 NOT NULL,
    daily_budget_amount numeric(12,4) DEFAULT 50.0000 NOT NULL,
    memory_enabled character(1) DEFAULT '0'::bpchar NOT NULL,
    compaction_trigger_tokens integer DEFAULT 60000 NOT NULL,
    create_dept bigint,
    create_by bigint,
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    update_by bigint,
    update_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    websearch_limit_per_user integer DEFAULT 20 NOT NULL,
    digest_enabled character(1) DEFAULT '0'::bpchar NOT NULL
);


--
-- Name: dcs_assistant_conversation; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.dcs_assistant_conversation (
    id bigint NOT NULL,
    tenant_id character varying(20) NOT NULL,
    user_id bigint NOT NULL,
    thread_id character varying(128) NOT NULL,
    title character varying(200),
    last_message_time timestamp without time zone,
    status character varying(20) DEFAULT 'ACTIVE'::character varying NOT NULL,
    create_dept bigint,
    create_by bigint,
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    update_by bigint,
    update_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    del_flag character(1) DEFAULT '0'::bpchar,
    todo_items text
);


--
-- Name: dcs_assistant_favorite; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.dcs_assistant_favorite (
    id bigint NOT NULL,
    tenant_id character varying(20) DEFAULT '000000'::character varying NOT NULL,
    user_id bigint NOT NULL,
    content character varying(200) NOT NULL,
    create_dept bigint,
    create_by bigint,
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    update_by bigint,
    update_time timestamp without time zone,
    del_flag character(1) DEFAULT '0'::bpchar
);


--
-- Name: dcs_assistant_feedback; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.dcs_assistant_feedback (
    id bigint NOT NULL,
    tenant_id character varying(20) NOT NULL,
    user_id bigint NOT NULL,
    message_id bigint,
    rating character varying(8) NOT NULL,
    note character varying(500),
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP
);


--
-- Name: dcs_assistant_memory; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.dcs_assistant_memory (
    id bigint NOT NULL,
    tenant_id character varying(20) DEFAULT '000000'::character varying NOT NULL,
    user_id bigint NOT NULL,
    content character varying(200) NOT NULL,
    source character varying(20) DEFAULT 'AI'::character varying NOT NULL,
    create_dept bigint,
    create_by bigint,
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    update_by bigint,
    update_time timestamp without time zone,
    del_flag character(1) DEFAULT '0'::bpchar
);


--
-- Name: dcs_assistant_message; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.dcs_assistant_message (
    id bigint NOT NULL,
    tenant_id character varying(20) NOT NULL,
    conversation_id bigint NOT NULL,
    user_id bigint NOT NULL,
    role character varying(16) NOT NULL,
    content text,
    total_tokens bigint,
    model character varying(128),
    end_reason character varying(16),
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    tool_steps text,
    followup_suggestions text
);


--
-- Name: dcs_assistant_model_setting; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.dcs_assistant_model_setting (
    id bigint NOT NULL,
    tenant_id character varying(20) NOT NULL,
    assistant_default_config_id bigint NOT NULL,
    allow_user_switch character(1) DEFAULT '0'::bpchar NOT NULL,
    switch_scope text,
    create_dept bigint,
    create_by bigint,
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    update_by bigint,
    update_time timestamp without time zone,
    del_flag character(1) DEFAULT '0'::bpchar,
    backend_default_config_id bigint,
    backend_thinking_tier character varying(16),
    backend_max_tokens integer
);


--
-- Name: dcs_assistant_run; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.dcs_assistant_run (
    id bigint NOT NULL,
    tenant_id character varying(20) NOT NULL,
    user_id bigint NOT NULL,
    thread_id character varying(128) NOT NULL,
    run_id character varying(128) NOT NULL,
    status character varying(20) DEFAULT 'RUNNING'::character varying NOT NULL,
    end_reason character varying(64),
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    update_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP
);


--
-- Name: dcs_assistant_user_quota; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.dcs_assistant_user_quota (
    id bigint NOT NULL,
    tenant_id character varying(20) NOT NULL,
    user_id bigint NOT NULL,
    round_limit_override integer,
    websearch_limit_override integer,
    disabled character(1) DEFAULT '0'::bpchar NOT NULL,
    create_dept bigint,
    create_by bigint,
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    update_by bigint,
    update_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP
);


--
-- Name: dcs_audit; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.dcs_audit (
    id bigint NOT NULL,
    tenant_id character varying(20) DEFAULT '000000'::character varying NOT NULL,
    submission_id bigint NOT NULL,
    task_id bigint NOT NULL,
    auditor_user_id bigint NOT NULL,
    decision character varying(10) NOT NULL,
    comment character varying(500),
    create_time timestamp without time zone,
    CONSTRAINT ck_dcs_audit_decision CHECK (((decision)::text = ANY ((ARRAY['PASS'::character varying, 'REJECT'::character varying])::text[])))
);


--
-- Name: dcs_captcha_log; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.dcs_captcha_log (
    log_id bigint NOT NULL,
    tenant_id character varying(20) DEFAULT '000000'::character varying,
    token character varying(64) NOT NULL,
    level smallint NOT NULL,
    user_id bigint,
    ip character varying(64),
    scene character varying(40),
    verified character(1) DEFAULT '0'::bpchar,
    fail_reason character varying(100),
    created_time timestamp without time zone DEFAULT now() NOT NULL,
    verified_time timestamp without time zone,
    create_dept bigint,
    create_by bigint,
    update_by bigint,
    update_time timestamp without time zone,
    action character varying(160),
    create_time timestamp without time zone
);


--
-- Name: dcs_file_guard_log; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.dcs_file_guard_log (
    log_id bigint NOT NULL,
    tenant_id character varying(20) DEFAULT '000000'::character varying NOT NULL,
    user_id bigint,
    user_name character varying(50),
    file_name character varying(255),
    file_size bigint,
    rule_code character varying(64),
    detail character varying(500),
    mode character varying(16),
    disposition character varying(16),
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    create_dept bigint,
    create_by bigint,
    update_by bigint,
    update_time timestamp without time zone
);


--
-- Name: dcs_file_review; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.dcs_file_review (
    review_id bigint NOT NULL,
    tenant_id character varying(20) DEFAULT '000000'::character varying NOT NULL,
    oss_id bigint NOT NULL,
    file_name character varying(255),
    file_category character varying(16) NOT NULL,
    file_size bigint,
    biz_source character varying(32) DEFAULT 'OTHER'::character varying NOT NULL,
    mission_id bigint,
    uploader_id bigint NOT NULL,
    uploader_name character varying(50),
    status character varying(16) DEFAULT 'PENDING'::character varying NOT NULL,
    reviewer_id bigint,
    reviewer_name character varying(50),
    review_time timestamp without time zone,
    reject_reason character varying(500),
    create_dept bigint,
    create_by bigint,
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    update_by bigint,
    update_time timestamp without time zone,
    guard_reason character varying(500),
    CONSTRAINT ck_dcs_file_review_status CHECK (((status)::text = ANY (ARRAY[('PENDING'::character varying)::text, ('APPROVED'::character varying)::text, ('REJECTED'::character varying)::text, ('EXEMPT'::character varying)::text])))
);


--
-- Name: dcs_file_review_config; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.dcs_file_review_config (
    config_id bigint NOT NULL,
    tenant_id character varying(20) NOT NULL,
    enabled character(1) DEFAULT '0'::bpchar NOT NULL,
    categories character varying(128) DEFAULT ''::character varying NOT NULL,
    create_dept bigint,
    create_by bigint,
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    update_by bigint,
    update_time timestamp without time zone,
    guard_mode character varying(16) DEFAULT 'OBSERVE'::character varying NOT NULL,
    guard_whitelist text,
    guard_deep_scan_max_mb integer DEFAULT 50 NOT NULL
);


--
-- Name: dcs_notice; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.dcs_notice (
    id bigint NOT NULL,
    tenant_id character varying(20) DEFAULT '000000'::character varying NOT NULL,
    user_id bigint NOT NULL,
    notice_type character varying(30) NOT NULL,
    title character varying(200) NOT NULL,
    content character varying(1000),
    task_id bigint,
    read_flag character(1) DEFAULT '0'::bpchar NOT NULL,
    create_time timestamp without time zone,
    dedup_key character varying(128)
);


--
-- Name: dcs_profile_review; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.dcs_profile_review (
    review_id bigint NOT NULL,
    tenant_id character varying(20) NOT NULL,
    user_id bigint NOT NULL,
    score_id bigint NOT NULL,
    rule_version character varying(100),
    profile_risk_level character varying(20) NOT NULL,
    status character varying(20) DEFAULT 'PENDING'::character varying NOT NULL,
    trigger_reason character varying(500) NOT NULL,
    conclusion character varying(20),
    review_reason character varying(500),
    reviewed_by bigint,
    reviewed_time timestamp without time zone,
    create_dept bigint,
    create_by bigint,
    create_time timestamp without time zone DEFAULT now(),
    update_by bigint,
    update_time timestamp without time zone,
    CONSTRAINT ck_dcs_profile_review_level CHECK (((profile_risk_level)::text = 'HIGH'::text)),
    CONSTRAINT ck_dcs_profile_review_status CHECK (((status)::text = ANY (ARRAY[('PENDING'::character varying)::text, ('CONFIRMED'::character varying)::text, ('DISMISSED'::character varying)::text, ('SUPERSEDED'::character varying)::text])))
);


--
-- Name: dcs_risk_action; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.dcs_risk_action (
    action_id bigint NOT NULL,
    tenant_id character varying(20) NOT NULL,
    user_id bigint NOT NULL,
    ip_masked character varying(80),
    risk_level integer NOT NULL,
    risk_score integer DEFAULT 0 NOT NULL,
    source_track character varying(40),
    action_type character varying(40) NOT NULL,
    trigger_key character varying(160) NOT NULL,
    trigger_reason character varying(500) NOT NULL,
    status character varying(20) DEFAULT 'ACTIVE'::character varying NOT NULL,
    start_time timestamp without time zone DEFAULT now() NOT NULL,
    expire_time timestamp without time zone NOT NULL,
    released_by bigint,
    released_time timestamp without time zone,
    release_reason character varying(500),
    review_status character varying(20) DEFAULT 'PENDING'::character varying NOT NULL,
    reviewed_by bigint,
    reviewed_time timestamp without time zone,
    review_conclusion character varying(20),
    review_reason character varying(500),
    create_dept bigint,
    create_by bigint,
    create_time timestamp without time zone DEFAULT now(),
    update_by bigint,
    update_time timestamp without time zone,
    CONSTRAINT ck_dcs_risk_action_level CHECK ((risk_level = ANY (ARRAY[3, 4]))),
    CONSTRAINT ck_dcs_risk_action_review CHECK (((review_status)::text = ANY (ARRAY[('PENDING'::character varying)::text, ('CONFIRMED'::character varying)::text, ('DISMISSED'::character varying)::text]))),
    CONSTRAINT ck_dcs_risk_action_status CHECK (((status)::text = ANY (ARRAY[('ACTIVE'::character varying)::text, ('EXPIRED'::character varying)::text, ('RELEASED'::character varying)::text]))),
    CONSTRAINT ck_dcs_risk_action_type CHECK (((action_type)::text = ANY (ARRAY[('ENHANCED_VERIFICATION'::character varying)::text, ('TEMP_FREEZE'::character varying)::text])))
);


--
-- Name: dcs_risk_event; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.dcs_risk_event (
    event_id bigint NOT NULL,
    tenant_id character varying(20) DEFAULT '000000'::character varying,
    user_id bigint,
    ip character varying(64),
    user_agent character varying(255),
    event_type character varying(40) NOT NULL,
    source character varying(40) DEFAULT 'CHAT'::character varying,
    ref_id bigint,
    detail text,
    risk_score integer DEFAULT 0 NOT NULL,
    created_time timestamp without time zone DEFAULT now() NOT NULL,
    create_dept bigint,
    create_by bigint,
    create_time timestamp without time zone DEFAULT now(),
    update_by bigint,
    update_time timestamp without time zone,
    occurred_time timestamp without time zone,
    idempotency_key character varying(160),
    hit_word character varying(100)
);


--
-- Name: dcs_risk_rule; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.dcs_risk_rule (
    rule_id bigint NOT NULL,
    tenant_id character varying(20) DEFAULT '000000'::character varying,
    event_type character varying(40) NOT NULL,
    weight integer DEFAULT 2 NOT NULL,
    enabled character(1) DEFAULT '1'::bpchar,
    threshold_l1 integer DEFAULT 20 NOT NULL,
    threshold_l2 integer DEFAULT 40 NOT NULL,
    threshold_l3 integer DEFAULT 60 NOT NULL,
    threshold_l4 integer DEFAULT 80 NOT NULL,
    window_minutes integer DEFAULT 60 NOT NULL,
    description character varying(200),
    create_by bigint,
    create_time timestamp without time zone DEFAULT now(),
    update_by bigint,
    update_time timestamp without time zone,
    create_dept bigint,
    rule_scope character varying(16) DEFAULT 'EVENT'::character varying NOT NULL
);


--
-- Name: dcs_risk_whitelist; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.dcs_risk_whitelist (
    whitelist_id bigint NOT NULL,
    tenant_id character varying(20) DEFAULT '000000'::character varying,
    list_type character varying(20) NOT NULL,
    value character varying(200) NOT NULL,
    reason character varying(200),
    enabled character(1) DEFAULT '1'::bpchar,
    create_by bigint,
    create_time timestamp without time zone DEFAULT now(),
    update_by bigint,
    update_time timestamp without time zone,
    create_dept bigint
);


--
-- Name: dcs_sensitive_word; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.dcs_sensitive_word (
    word_id bigint NOT NULL,
    tenant_id character varying(20) DEFAULT '000000'::character varying NOT NULL,
    word_text character varying(100) NOT NULL,
    category character varying(50) DEFAULT 'GENERAL'::character varying NOT NULL,
    action character varying(20) DEFAULT 'MASK'::character varying NOT NULL,
    enabled character(1) DEFAULT '1'::bpchar NOT NULL,
    builtin character(1) DEFAULT '0'::bpchar NOT NULL,
    remark character varying(200),
    create_dept bigint,
    create_by bigint,
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    update_by bigint,
    update_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    del_flag character(1) DEFAULT '0'::bpchar,
    scene character varying(50) DEFAULT 'CHAT'::character varying NOT NULL
);


--
-- Name: dcs_service_charge_rule; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.dcs_service_charge_rule (
    rule_id bigint NOT NULL,
    tenant_id character varying(20) DEFAULT '000000'::character varying NOT NULL,
    service_code character varying(64) NOT NULL,
    service_name character varying(128) NOT NULL,
    charge_mode character varying(32) DEFAULT 'AI_USAGE_COST'::character varying NOT NULL,
    fallback_policy character varying(32) DEFAULT 'BLOCK'::character varying NOT NULL,
    wallet_scope character varying(8) DEFAULT 'MAIN'::character varying NOT NULL,
    fixed_amount numeric(14,4),
    low_balance_threshold numeric(14,4) DEFAULT 1.00 NOT NULL,
    enabled character(1) DEFAULT '1'::bpchar NOT NULL,
    sort_order integer DEFAULT 0 NOT NULL,
    remark character varying(500),
    create_dept bigint,
    create_by bigint,
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    update_by bigint,
    update_time timestamp without time zone,
    del_flag character(1) DEFAULT '0'::bpchar NOT NULL
);


--
-- Name: dcs_submission; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.dcs_submission (
    id bigint NOT NULL,
    tenant_id character varying(20) DEFAULT '000000'::character varying NOT NULL,
    task_id bigint NOT NULL,
    submitter_user_id bigint NOT NULL,
    version integer DEFAULT 1 NOT NULL,
    status character varying(20) DEFAULT 'PENDING_AUDIT'::character varying NOT NULL,
    data jsonb DEFAULT '{}'::jsonb NOT NULL,
    submitted_at timestamp without time zone,
    audited_at timestamp without time zone,
    create_time timestamp without time zone,
    CONSTRAINT ck_dcs_submission_status CHECK (((status)::text = ANY ((ARRAY['PENDING_AUDIT'::character varying, 'APPROVED'::character varying, 'REJECTED'::character varying])::text[])))
);


--
-- Name: dcs_task; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.dcs_task (
    id bigint NOT NULL,
    tenant_id character varying(20) DEFAULT '000000'::character varying NOT NULL,
    title character varying(128) NOT NULL,
    description character varying(1000),
    publisher_user_id bigint NOT NULL,
    status character varying(20) DEFAULT 'DRAFT'::character varying NOT NULL,
    deadline timestamp without time zone,
    publish_time timestamp without time zone,
    assignee_user_ids jsonb DEFAULT '[]'::jsonb NOT NULL,
    audit_mode character varying(20) DEFAULT 'PUBLISHER'::character varying NOT NULL,
    auditor_user_ids jsonb DEFAULT '[]'::jsonb NOT NULL,
    create_dept bigint,
    create_by bigint,
    create_time timestamp without time zone,
    update_by bigint,
    update_time timestamp without time zone,
    CONSTRAINT ck_dcs_task_audit_mode CHECK (((audit_mode)::text = ANY ((ARRAY['PUBLISHER'::character varying, 'ASSIGNED'::character varying])::text[]))),
    CONSTRAINT ck_dcs_task_status CHECK (((status)::text = ANY ((ARRAY['DRAFT'::character varying, 'COLLECTING'::character varying, 'CLOSED'::character varying])::text[])))
);


--
-- Name: dcs_task_field; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.dcs_task_field (
    id bigint NOT NULL,
    tenant_id character varying(20) DEFAULT '000000'::character varying NOT NULL,
    task_id bigint NOT NULL,
    field_code character varying(64) NOT NULL,
    field_name character varying(64) NOT NULL,
    field_type character varying(20) DEFAULT 'text'::character varying NOT NULL,
    required character(1) DEFAULT '1'::bpchar NOT NULL,
    options jsonb DEFAULT '[]'::jsonb NOT NULL,
    order_num integer DEFAULT 0 NOT NULL,
    create_time timestamp without time zone
);


--
-- Name: dcs_tenant_billing_mode; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.dcs_tenant_billing_mode (
    tenant_id character varying(20) NOT NULL,
    consume_mode character varying(10) DEFAULT 'WALLET'::character varying NOT NULL,
    update_by bigint,
    update_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT dcs_tenant_billing_mode_ck CHECK (((consume_mode)::text = ANY (ARRAY[('WALLET'::character varying)::text, ('CREDIT'::character varying)::text])))
);


--
-- Name: dcs_tenant_wallet; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.dcs_tenant_wallet (
    wallet_id bigint NOT NULL,
    tenant_id character varying(20) DEFAULT '000000'::character varying NOT NULL,
    wallet_type character varying(8) DEFAULT 'MAIN'::character varying NOT NULL,
    parent_wallet_id bigint,
    balance_amount numeric(14,4) DEFAULT 0 NOT NULL,
    frozen_amount numeric(14,4) DEFAULT 0 NOT NULL,
    currency character varying(16) DEFAULT 'CNY'::character varying NOT NULL,
    status character varying(16) DEFAULT 'ACTIVE'::character varying NOT NULL,
    low_balance_threshold numeric(14,4) DEFAULT 1.00 NOT NULL,
    negative_allowed character(1) DEFAULT '0'::bpchar NOT NULL,
    remark character varying(500),
    version integer DEFAULT 0 NOT NULL,
    create_dept bigint,
    create_by bigint,
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    update_by bigint,
    update_time timestamp without time zone,
    del_flag character(1) DEFAULT '0'::bpchar NOT NULL
);


--
-- Name: dcs_tenant_wallet_ledger; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.dcs_tenant_wallet_ledger (
    ledger_id bigint NOT NULL,
    tenant_id character varying(20) DEFAULT '000000'::character varying NOT NULL,
    wallet_id bigint NOT NULL,
    biz_type character varying(16) NOT NULL,
    biz_id bigint,
    service_code character varying(64),
    direction character varying(8) NOT NULL,
    amount numeric(14,4) NOT NULL,
    currency character varying(16) DEFAULT 'CNY'::character varying NOT NULL,
    balance_before numeric(14,4),
    balance_after numeric(14,4),
    idempotency_key character varying(128) NOT NULL,
    operator_id bigint,
    extra_json text,
    create_dept bigint,
    create_by bigint,
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    update_by bigint,
    update_time timestamp without time zone,
    del_flag character(1) DEFAULT '0'::bpchar NOT NULL
);


--
-- Name: dcs_wallet_alert_state; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.dcs_wallet_alert_state (
    alert_id bigint NOT NULL,
    tenant_id character varying(20) DEFAULT '000000'::character varying NOT NULL,
    wallet_id bigint NOT NULL,
    alert_type character varying(32) NOT NULL,
    last_alert_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    threshold_amount numeric(14,4),
    create_dept bigint,
    create_by bigint,
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    update_by bigint,
    update_time timestamp without time zone,
    del_flag character(1) DEFAULT '0'::bpchar NOT NULL
);


--
-- Name: dcs_wallet_payment_order; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.dcs_wallet_payment_order (
    order_id bigint NOT NULL,
    tenant_id character varying(20) DEFAULT '000000'::character varying NOT NULL,
    wallet_id bigint NOT NULL,
    order_no character varying(64) NOT NULL,
    channel character varying(16) DEFAULT 'MANUAL'::character varying NOT NULL,
    amount numeric(14,4) NOT NULL,
    currency character varying(16) DEFAULT 'CNY'::character varying NOT NULL,
    status character varying(16) DEFAULT 'PENDING'::character varying NOT NULL,
    pay_time timestamp without time zone,
    notify_payload text,
    idempotency_key character varying(128),
    operator_id bigint,
    remark character varying(500),
    create_dept bigint,
    create_by bigint,
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    update_by bigint,
    update_time timestamp without time zone,
    del_flag character(1) DEFAULT '0'::bpchar NOT NULL
);


--
-- Name: dcs_word_fp_feedback; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.dcs_word_fp_feedback (
    feedback_id bigint NOT NULL,
    tenant_id character varying(20) DEFAULT '000000'::character varying NOT NULL,
    word_text character varying(100) NOT NULL,
    detail_id bigint NOT NULL,
    mission_id bigint NOT NULL,
    reporter_id bigint,
    create_dept bigint,
    create_by bigint,
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    update_by bigint,
    update_time timestamp without time zone
);


--
-- Name: profile_event; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.profile_event (
    event_id bigint NOT NULL,
    tenant_id character varying(20) DEFAULT '000000'::character varying NOT NULL,
    source character varying(32) NOT NULL,
    event_type character varying(64) NOT NULL,
    event_name character varying(64),
    event_uid character varying(128) NOT NULL,
    subject_type character varying(32) DEFAULT 'user'::character varying NOT NULL,
    subject_id character varying(64) NOT NULL,
    anonymous_id character varying(128),
    session_id character varying(128),
    schema_version character varying(32) DEFAULT '1-0-0'::character varying NOT NULL,
    occurred_time timestamp without time zone NOT NULL,
    props jsonb DEFAULT '{}'::jsonb NOT NULL,
    context jsonb DEFAULT '{}'::jsonb NOT NULL,
    traits jsonb DEFAULT '{}'::jsonb NOT NULL,
    identifiers jsonb DEFAULT '{}'::jsonb NOT NULL,
    create_dept bigint,
    create_by bigint,
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    update_by bigint,
    update_time timestamp without time zone,
    del_flag character(1) DEFAULT '0'::bpchar
);


--
-- Name: profile_recalc_batch; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.profile_recalc_batch (
    batch_id bigint NOT NULL,
    tenant_id character varying(20) NOT NULL,
    source character varying(32) NOT NULL,
    status character varying(16) DEFAULT 'RUNNING'::character varying NOT NULL,
    total_subjects integer,
    done_subjects integer DEFAULT 0 NOT NULL,
    last_subject_id bigint,
    error_msg text,
    create_dept bigint,
    create_by bigint,
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    update_by bigint,
    update_time timestamp without time zone,
    del_flag character(1) DEFAULT '0'::bpchar
);


--
-- Name: profile_score_dimension; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.profile_score_dimension (
    dimension_id bigint NOT NULL,
    tenant_id character varying(20) DEFAULT '000000'::character varying NOT NULL,
    dim_code character varying(64) NOT NULL,
    dim_name character varying(100) NOT NULL,
    source character varying(32) NOT NULL,
    calc_mode character varying(16) DEFAULT 'EVENT_AGG'::character varying NOT NULL,
    calc_config jsonb DEFAULT '{}'::jsonb NOT NULL,
    score_max numeric(6,2) DEFAULT 100 NOT NULL,
    enabled character(1) DEFAULT '1'::bpchar NOT NULL,
    builtin character(1) DEFAULT '0'::bpchar NOT NULL,
    sort_order integer DEFAULT 0 NOT NULL,
    remark character varying(500),
    create_dept bigint,
    create_by bigint,
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    update_by bigint,
    update_time timestamp without time zone,
    del_flag character(1) DEFAULT '0'::bpchar
);


--
-- Name: profile_score_rule; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.profile_score_rule (
    rule_id bigint NOT NULL,
    tenant_id character varying(20) DEFAULT '000000'::character varying NOT NULL,
    source character varying(32) NOT NULL,
    rule_version character varying(32) NOT NULL,
    rule_name character varying(100),
    status character varying(16) DEFAULT 'PUBLISHED'::character varying NOT NULL,
    weights jsonb DEFAULT '{}'::jsonb NOT NULL,
    remark character varying(500),
    published_at timestamp without time zone,
    published_by bigint,
    base_rule_version character varying(32),
    revision integer DEFAULT 0 NOT NULL,
    enabled character(1) DEFAULT '1'::bpchar NOT NULL,
    create_dept bigint,
    create_by bigint,
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    update_by bigint,
    update_time timestamp without time zone,
    del_flag character(1) DEFAULT '0'::bpchar
);


--
-- Name: profile_score_snapshot; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.profile_score_snapshot (
    snapshot_id bigint NOT NULL,
    tenant_id character varying(20) NOT NULL,
    source character varying(32) NOT NULL,
    subject_type character varying(32) NOT NULL,
    subject_id character varying(64) NOT NULL,
    total_score numeric(6,2) NOT NULL,
    dimension_detail jsonb DEFAULT '{}'::jsonb NOT NULL,
    rule_version character varying(32) NOT NULL,
    prev_snapshot_id bigint,
    calc_time timestamp without time zone NOT NULL,
    create_dept bigint,
    create_by bigint,
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    update_by bigint,
    update_time timestamp without time zone,
    del_flag character(1) DEFAULT '0'::bpchar
);


--
-- Name: profile_subject; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.profile_subject (
    id bigint NOT NULL,
    tenant_id character varying(20) DEFAULT '000000'::character varying NOT NULL,
    source character varying(32) NOT NULL,
    subject_type character varying(32) DEFAULT 'user'::character varying NOT NULL,
    subject_id character varying(64) NOT NULL,
    display_name character varying(128),
    first_event_time timestamp without time zone,
    last_event_time timestamp without time zone,
    event_count bigint DEFAULT 0 NOT NULL,
    properties jsonb DEFAULT '{}'::jsonb NOT NULL,
    create_dept bigint,
    create_by bigint,
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    update_by bigint,
    update_time timestamp without time zone,
    del_flag character(1) DEFAULT '0'::bpchar
);


--
-- Name: profile_protocol; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.profile_protocol (
    protocol_id bigint NOT NULL,
    tenant_id character varying(20) DEFAULT '000000'::character varying NOT NULL,
    source character varying(32) NOT NULL,
    version character varying(32) NOT NULL,
    status character varying(16) DEFAULT 'PUBLISHED'::character varying NOT NULL,
    manifest jsonb NOT NULL,
    checksum character varying(64) NOT NULL,
    published_at timestamp without time zone,
    published_by bigint,
    create_dept bigint,
    create_by bigint,
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    update_by bigint,
    update_time timestamp without time zone,
    del_flag character(1) DEFAULT '0'::bpchar,
    CONSTRAINT pk_profile_protocol PRIMARY KEY (protocol_id),
    CONSTRAINT uk_profile_protocol_version UNIQUE (tenant_id, source, version)
);


--
-- Name: profile_schema_registry; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.profile_schema_registry (
    schema_id bigint NOT NULL,
    tenant_id character varying(20) DEFAULT '000000'::character varying NOT NULL,
    source character varying(32) NOT NULL,
    event_name character varying(64) NOT NULL,
    schema_version character varying(32) DEFAULT '1-0-0'::character varying NOT NULL,
    schema_json jsonb NOT NULL,
    status character varying(16) DEFAULT 'ACTIVE'::character varying NOT NULL,
    create_dept bigint,
    create_by bigint,
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    update_by bigint,
    update_time timestamp without time zone,
    del_flag character(1) DEFAULT '0'::bpchar,
    CONSTRAINT pk_profile_schema_registry PRIMARY KEY (schema_id),
    CONSTRAINT uk_profile_schema UNIQUE (tenant_id, source, event_name, schema_version)
);


--
-- Name: profile_identity_link; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.profile_identity_link (
    link_id bigint NOT NULL,
    tenant_id character varying(20) DEFAULT '000000'::character varying NOT NULL,
    source character varying(32) NOT NULL,
    subject_type character varying(32) DEFAULT 'user'::character varying NOT NULL,
    subject_id character varying(64) NOT NULL,
    identifier_type character varying(32) NOT NULL,
    identifier_value character varying(128) NOT NULL,
    identifier_hash character varying(128),
    confidence numeric(5,4) DEFAULT 1.0 NOT NULL,
    is_secure character(1) DEFAULT '0'::bpchar NOT NULL,
    status character varying(16) DEFAULT 'ACTIVE'::character varying NOT NULL,
    first_seen timestamp without time zone,
    last_seen timestamp without time zone,
    create_dept bigint,
    create_by bigint,
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    update_by bigint,
    update_time timestamp without time zone,
    CONSTRAINT pk_profile_identity_link PRIMARY KEY (link_id),
    CONSTRAINT uk_profile_identity_link UNIQUE (tenant_id, source, identifier_type, identifier_value),
    CONSTRAINT ck_profile_identity_confidence CHECK (confidence >= 0 AND confidence <= 1)
);


--
-- Name: profile_identity_merge; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.profile_identity_merge (
    merge_id bigint NOT NULL,
    tenant_id character varying(20) DEFAULT '000000'::character varying NOT NULL,
    source character varying(32) NOT NULL,
    subject_type character varying(32) DEFAULT 'user'::character varying NOT NULL,
    from_subject_id character varying(64) NOT NULL,
    to_subject_id character varying(64) NOT NULL,
    reason character varying(32) NOT NULL,
    rule_version character varying(32) NOT NULL,
    actor character varying(64),
    occurred_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    reversible_until timestamp without time zone,
    create_dept bigint,
    create_by bigint,
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    update_by bigint,
    update_time timestamp without time zone,
    CONSTRAINT pk_profile_identity_merge PRIMARY KEY (merge_id),
    CONSTRAINT ck_profile_identity_merge_diff CHECK (from_subject_id <> to_subject_id)
);


--
-- Name: profile_score_model; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.profile_score_model (
    model_id bigint NOT NULL,
    tenant_id character varying(20) DEFAULT '000000'::character varying NOT NULL,
    source character varying(32) NOT NULL,
    model_code character varying(64) NOT NULL,
    model_name character varying(100) NOT NULL,
    model_type character varying(32) DEFAULT 'GENERIC'::character varying NOT NULL,
    model_version character varying(32) DEFAULT 'v1'::character varying NOT NULL,
    config jsonb DEFAULT '{}'::jsonb NOT NULL,
    score_max numeric(8,2) DEFAULT 100 NOT NULL,
    enabled character(1) DEFAULT '1'::bpchar NOT NULL,
    builtin character(1) DEFAULT '0'::bpchar NOT NULL,
    remark character varying(500),
    revision integer DEFAULT 0 NOT NULL,
    create_dept bigint,
    create_by bigint,
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    update_by bigint,
    update_time timestamp without time zone,
    del_flag character(1) DEFAULT '0'::bpchar,
    CONSTRAINT pk_profile_score_model PRIMARY KEY (model_id)
);

CREATE UNIQUE INDEX uk_profile_model_code
    ON public.profile_score_model USING btree (tenant_id, source, model_code)
    WHERE (del_flag = '0'::bpchar);


--
-- Name: profile_model_run; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.profile_model_run (
    run_id bigint NOT NULL,
    tenant_id character varying(20) NOT NULL,
    source character varying(32) NOT NULL,
    model_id bigint,
    model_code character varying(64),
    model_version character varying(32),
    status character varying(16) DEFAULT 'RUNNING'::character varying NOT NULL,
    trigger_type character varying(16) DEFAULT 'MANUAL'::character varying NOT NULL,
    total_subjects integer DEFAULT 0 NOT NULL,
    done_subjects integer DEFAULT 0 NOT NULL,
    error_msg text,
    started_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    finished_at timestamp without time zone,
    create_dept bigint,
    create_by bigint,
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    update_by bigint,
    update_time timestamp without time zone,
    del_flag character(1) DEFAULT '0'::bpchar,
    CONSTRAINT pk_profile_model_run PRIMARY KEY (run_id)
);

CREATE INDEX idx_profile_model_run_model
    ON public.profile_model_run USING btree (tenant_id, source, model_code, create_time DESC);


--
-- Name: profile_model_snapshot; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.profile_model_snapshot (
    model_snapshot_id bigint NOT NULL,
    tenant_id character varying(20) NOT NULL,
    source character varying(32) NOT NULL,
    model_id bigint,
    model_code character varying(64) NOT NULL,
    model_version character varying(32) NOT NULL,
    subject_type character varying(32) DEFAULT 'user'::character varying NOT NULL,
    subject_id character varying(64) NOT NULL,
    model_score numeric(8,2) DEFAULT 0 NOT NULL,
    normalized_score numeric(8,2) DEFAULT 0 NOT NULL,
    level character varying(64),
    detail jsonb DEFAULT '{}'::jsonb NOT NULL,
    run_id bigint,
    prev_snapshot_id bigint,
    calc_time timestamp without time zone NOT NULL,
    create_dept bigint,
    create_by bigint,
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    update_by bigint,
    update_time timestamp without time zone,
    del_flag character(1) DEFAULT '0'::bpchar,
    CONSTRAINT pk_profile_model_snapshot PRIMARY KEY (model_snapshot_id)
);

CREATE INDEX idx_profile_model_snap_latest
    ON public.profile_model_snapshot USING btree
    (tenant_id, source, model_code, subject_type, subject_id, calc_time DESC, model_snapshot_id DESC);
CREATE INDEX idx_profile_model_snap_run
    ON public.profile_model_snapshot USING btree (tenant_id, run_id);
CREATE INDEX idx_profile_model_snap_level
    ON public.profile_model_snapshot USING btree (tenant_id, source, model_code, level);


--
-- Name: profile_segment; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.profile_segment (
    segment_id bigint NOT NULL,
    tenant_id character varying(20) DEFAULT '000000'::character varying NOT NULL,
    source character varying(32) NOT NULL,
    segment_code character varying(64) NOT NULL,
    segment_name character varying(100) NOT NULL,
    segment_type character varying(16) DEFAULT 'DYNAMIC'::character varying NOT NULL,
    status character varying(16) DEFAULT 'ENABLED'::character varying NOT NULL,
    rule_config jsonb DEFAULT '{}'::jsonb NOT NULL,
    description character varying(500),
    member_count integer DEFAULT 0 NOT NULL,
    last_run_id bigint,
    last_eval_time timestamp without time zone,
    builtin character(1) DEFAULT '0'::bpchar NOT NULL,
    revision integer DEFAULT 0 NOT NULL,
    create_dept bigint,
    create_by bigint,
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    update_by bigint,
    update_time timestamp without time zone,
    del_flag character(1) DEFAULT '0'::bpchar,
    CONSTRAINT pk_profile_segment PRIMARY KEY (segment_id)
);

CREATE UNIQUE INDEX uk_profile_segment_code
    ON public.profile_segment USING btree (tenant_id, source, segment_code)
    WHERE (del_flag = '0'::bpchar);


--
-- Name: profile_segment_run; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.profile_segment_run (
    run_id bigint NOT NULL,
    tenant_id character varying(20) NOT NULL,
    source character varying(32) NOT NULL,
    segment_id bigint NOT NULL,
    trigger_type character varying(16) DEFAULT 'MANUAL'::character varying NOT NULL,
    status character varying(16) DEFAULT 'RUNNING'::character varying NOT NULL,
    total_candidates integer DEFAULT 0 NOT NULL,
    member_count integer DEFAULT 0 NOT NULL,
    error_msg text,
    started_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    finished_at timestamp without time zone,
    create_dept bigint,
    create_by bigint,
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    update_by bigint,
    update_time timestamp without time zone,
    del_flag character(1) DEFAULT '0'::bpchar,
    CONSTRAINT pk_profile_segment_run PRIMARY KEY (run_id)
);

CREATE INDEX idx_profile_segment_run_segment
    ON public.profile_segment_run USING btree (tenant_id, segment_id, create_time DESC);


--
-- Name: profile_segment_member; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.profile_segment_member (
    member_id bigint NOT NULL,
    tenant_id character varying(20) NOT NULL,
    source character varying(32) NOT NULL,
    segment_id bigint NOT NULL,
    run_id bigint NOT NULL,
    subject_type character varying(32) DEFAULT 'user'::character varying NOT NULL,
    subject_id character varying(64) NOT NULL,
    display_name character varying(128),
    reason character varying(500),
    create_dept bigint,
    create_by bigint,
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    update_by bigint,
    update_time timestamp without time zone,
    del_flag character(1) DEFAULT '0'::bpchar,
    CONSTRAINT pk_profile_segment_member PRIMARY KEY (member_id)
);

CREATE UNIQUE INDEX uk_profile_segment_member
    ON public.profile_segment_member USING btree (run_id, subject_type, subject_id)
    WHERE (del_flag = '0'::bpchar);
CREATE INDEX idx_profile_segment_member_segment
    ON public.profile_segment_member USING btree (tenant_id, segment_id, subject_id);


--
-- Name: profile_review_queue; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.profile_review_queue (
    review_id bigint NOT NULL,
    tenant_id character varying(20) NOT NULL,
    source character varying(32) NOT NULL,
    subject_type character varying(32) DEFAULT 'user'::character varying NOT NULL,
    subject_id character varying(64) NOT NULL,
    display_name character varying(128),
    snapshot_id bigint,
    total_score numeric(6,2),
    risk_level character varying(20) DEFAULT 'HIGH'::character varying NOT NULL,
    trigger_reason character varying(500) NOT NULL,
    status character varying(20) DEFAULT 'PENDING'::character varying NOT NULL,
    conclusion character varying(32),
    review_note character varying(500),
    reviewed_by bigint,
    reviewed_at timestamp without time zone,
    create_dept bigint,
    create_by bigint,
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    update_by bigint,
    update_time timestamp without time zone,
    del_flag character(1) DEFAULT '0'::bpchar,
    CONSTRAINT pk_profile_review_queue PRIMARY KEY (review_id),
    CONSTRAINT ck_profile_review_status CHECK (
        (status)::text = ANY (ARRAY['PENDING', 'CONFIRMED', 'DISMISSED', 'SUPERSEDED']::text[])
    )
);

CREATE UNIQUE INDEX uk_profile_review_pending
    ON public.profile_review_queue USING btree (tenant_id, source, subject_type, subject_id)
    WHERE (status = 'PENDING'::character varying AND del_flag = '0'::bpchar);
CREATE INDEX idx_profile_review_queue
    ON public.profile_review_queue USING btree (tenant_id, source, status, create_time DESC);


--
-- Name: profile_engine_config; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.profile_engine_config (
    config_id bigint NOT NULL,
    tenant_id character varying(20) DEFAULT '000000'::character varying NOT NULL,
    source character varying(32) NOT NULL,
    auto_recalc character(1) DEFAULT '1'::bpchar NOT NULL,
    review_enabled character(1) DEFAULT '1'::bpchar NOT NULL,
    review_threshold numeric(6,2) DEFAULT 60 NOT NULL,
    debounce_ms integer DEFAULT 2000 NOT NULL,
    remark character varying(500),
    create_dept bigint,
    create_by bigint,
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    update_by bigint,
    update_time timestamp without time zone,
    del_flag character(1) DEFAULT '0'::bpchar,
    CONSTRAINT pk_profile_engine_config PRIMARY KEY (config_id)
);

CREATE UNIQUE INDEX uk_profile_engine_config
    ON public.profile_engine_config USING btree (tenant_id, source)
    WHERE (del_flag = '0'::bpchar);


--
-- Name: sys_client; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sys_client (
    id bigint NOT NULL,
    client_id character varying(64) DEFAULT ''::character varying,
    client_key character varying(32) DEFAULT ''::character varying,
    client_secret character varying(255) DEFAULT ''::character varying,
    grant_type character varying(255) DEFAULT ''::character varying,
    device_type character varying(32) DEFAULT ''::character varying,
    active_timeout integer DEFAULT 1800,
    timeout integer DEFAULT 604800,
    status character(1) DEFAULT '0'::bpchar,
    del_flag character(1) DEFAULT '0'::bpchar,
    create_dept bigint,
    create_by bigint,
    create_time timestamp(6) without time zone,
    update_by bigint,
    update_time timestamp(6) without time zone
);


--
-- Name: sys_config; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sys_config (
    config_id bigint NOT NULL,
    tenant_id character varying(20) DEFAULT '000000'::character varying,
    config_name character varying(100) DEFAULT ''::character varying,
    config_key character varying(100) DEFAULT ''::character varying,
    config_value character varying(500) DEFAULT ''::character varying,
    config_type character(1) DEFAULT 'N'::bpchar,
    create_dept bigint,
    create_by bigint,
    create_time timestamp(6) without time zone,
    update_by bigint,
    update_time timestamp(6) without time zone,
    remark character varying(500) DEFAULT NULL::character varying
);


--
-- Name: sys_dept; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sys_dept (
    dept_id bigint NOT NULL,
    tenant_id character varying(20) DEFAULT '000000'::character varying,
    parent_id bigint DEFAULT 0,
    ancestors character varying(500) DEFAULT ''::character varying,
    dept_name character varying(30) DEFAULT ''::character varying,
    dept_category character varying(100) DEFAULT NULL::character varying,
    order_num integer DEFAULT 0,
    leader bigint,
    phone character varying(11) DEFAULT NULL::character varying,
    email character varying(50) DEFAULT NULL::character varying,
    status character(1) DEFAULT '0'::bpchar,
    del_flag character(1) DEFAULT '0'::bpchar,
    create_dept bigint,
    create_by bigint,
    create_time timestamp(6) without time zone,
    update_by bigint,
    update_time timestamp(6) without time zone
);


--
-- Name: sys_dict_data; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sys_dict_data (
    dict_code bigint NOT NULL,
    tenant_id character varying(20) DEFAULT '000000'::character varying,
    dict_sort integer DEFAULT 0,
    dict_label character varying(100) DEFAULT ''::character varying,
    dict_value character varying(100) DEFAULT ''::character varying,
    dict_type character varying(100) DEFAULT ''::character varying,
    css_class character varying(100) DEFAULT NULL::character varying,
    list_class character varying(100) DEFAULT NULL::character varying,
    is_default character(1) DEFAULT 'N'::bpchar,
    create_dept bigint,
    create_by bigint,
    create_time timestamp(6) without time zone,
    update_by bigint,
    update_time timestamp(6) without time zone,
    remark character varying(500) DEFAULT NULL::character varying
);


--
-- Name: sys_dict_type; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sys_dict_type (
    dict_id bigint NOT NULL,
    tenant_id character varying(20) DEFAULT '000000'::character varying,
    dict_name character varying(100) DEFAULT ''::character varying,
    dict_type character varying(100) DEFAULT ''::character varying,
    create_dept bigint,
    create_by bigint,
    create_time timestamp(6) without time zone,
    update_by bigint,
    update_time timestamp(6) without time zone,
    remark character varying(500) DEFAULT NULL::character varying
);


--
-- Name: sys_logininfor; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sys_logininfor (
    info_id bigint NOT NULL,
    tenant_id character varying(20) DEFAULT '000000'::character varying,
    user_name character varying(50) DEFAULT ''::character varying,
    client_key character varying(32) DEFAULT ''::character varying,
    device_type character varying(32) DEFAULT ''::character varying,
    ipaddr character varying(128) DEFAULT ''::character varying,
    login_location character varying(255) DEFAULT ''::character varying,
    browser character varying(50) DEFAULT ''::character varying,
    os character varying(50) DEFAULT ''::character varying,
    status character(1) DEFAULT '0'::bpchar,
    msg character varying(255) DEFAULT ''::character varying,
    login_time timestamp(6) without time zone
);


--
-- Name: sys_menu; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sys_menu (
    menu_id bigint NOT NULL,
    menu_name character varying(50) NOT NULL,
    parent_id bigint DEFAULT 0,
    order_num integer DEFAULT 0,
    path character varying(200) DEFAULT ''::character varying,
    component character varying(255) DEFAULT NULL::character varying,
    query_param character varying(255) DEFAULT NULL::character varying,
    is_frame character(1) DEFAULT '1'::bpchar,
    is_cache character(1) DEFAULT '0'::bpchar,
    menu_type character(1) DEFAULT ''::bpchar,
    visible character(1) DEFAULT '0'::bpchar,
    status character(1) DEFAULT '0'::bpchar,
    perms character varying(100) DEFAULT NULL::character varying,
    icon character varying(100) DEFAULT '#'::character varying,
    create_dept bigint,
    create_by bigint,
    create_time timestamp(6) without time zone,
    update_by bigint,
    update_time timestamp(6) without time zone,
    remark character varying(500) DEFAULT ''::character varying
);


--
-- Name: sys_notice; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sys_notice (
    notice_id bigint NOT NULL,
    tenant_id character varying(20) DEFAULT '000000'::character varying,
    notice_title character varying(50) NOT NULL,
    notice_type character(1) NOT NULL,
    notice_content text,
    status character(1) DEFAULT '0'::bpchar,
    create_dept bigint,
    create_by bigint,
    create_time timestamp(6) without time zone,
    update_by bigint,
    update_time timestamp(6) without time zone,
    remark character varying(255) DEFAULT NULL::character varying
);


--
-- Name: sys_oper_log; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sys_oper_log (
    oper_id bigint NOT NULL,
    tenant_id character varying(20) DEFAULT '000000'::character varying,
    title character varying(50) DEFAULT ''::character varying,
    business_type integer DEFAULT 0,
    method character varying(100) DEFAULT ''::character varying,
    request_method character varying(10) DEFAULT ''::character varying,
    operator_type integer DEFAULT 0,
    oper_name character varying(50) DEFAULT ''::character varying,
    dept_name character varying(50) DEFAULT ''::character varying,
    oper_url character varying(255) DEFAULT ''::character varying,
    oper_ip character varying(128) DEFAULT ''::character varying,
    oper_location character varying(255) DEFAULT ''::character varying,
    oper_param character varying(4000) DEFAULT ''::character varying,
    json_result character varying(4000) DEFAULT ''::character varying,
    status integer DEFAULT 0,
    error_msg character varying(4000) DEFAULT ''::character varying,
    oper_time timestamp(6) without time zone,
    cost_time bigint DEFAULT 0
);


--
-- Name: sys_oss; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sys_oss (
    oss_id bigint NOT NULL,
    tenant_id character varying(20) DEFAULT '000000'::character varying,
    file_name character varying(255) DEFAULT ''::character varying NOT NULL,
    original_name character varying(255) DEFAULT ''::character varying NOT NULL,
    file_suffix character varying(10) DEFAULT ''::character varying NOT NULL,
    url character varying(500) DEFAULT ''::character varying NOT NULL,
    ext1 character varying(500) DEFAULT ''::character varying,
    create_dept bigint,
    create_by bigint,
    create_time timestamp(6) without time zone,
    update_by bigint,
    update_time timestamp(6) without time zone,
    service character varying(20) DEFAULT 'minio'::character varying
);


--
-- Name: sys_oss_config; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sys_oss_config (
    oss_config_id bigint NOT NULL,
    tenant_id character varying(20) DEFAULT '000000'::character varying,
    config_key character varying(20) DEFAULT ''::character varying NOT NULL,
    access_key character varying(255) DEFAULT ''::character varying,
    secret_key character varying(255) DEFAULT ''::character varying,
    bucket_name character varying(255) DEFAULT ''::character varying,
    prefix character varying(255) DEFAULT ''::character varying,
    endpoint character varying(255) DEFAULT ''::character varying,
    domain character varying(255) DEFAULT ''::character varying,
    is_https character(1) DEFAULT 'N'::bpchar,
    region character varying(255) DEFAULT ''::character varying,
    access_policy character(1) DEFAULT '1'::bpchar NOT NULL,
    status character(1) DEFAULT '1'::bpchar,
    ext1 character varying(255) DEFAULT ''::character varying,
    create_dept bigint,
    create_by bigint,
    create_time timestamp(6) without time zone,
    update_by bigint,
    update_time timestamp(6) without time zone,
    remark character varying(500) DEFAULT ''::character varying
);


--
-- Name: sys_post; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sys_post (
    post_id bigint NOT NULL,
    tenant_id character varying(20) DEFAULT '000000'::character varying,
    dept_id bigint,
    post_code character varying(64) NOT NULL,
    post_category character varying(100) DEFAULT NULL::character varying,
    post_name character varying(50) NOT NULL,
    post_sort integer NOT NULL,
    status character(1) NOT NULL,
    create_dept bigint,
    create_by bigint,
    create_time timestamp(6) without time zone,
    update_by bigint,
    update_time timestamp(6) without time zone,
    remark character varying(500) DEFAULT NULL::character varying
);


--
-- Name: sys_role; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sys_role (
    role_id bigint NOT NULL,
    tenant_id character varying(20) DEFAULT '000000'::character varying,
    role_name character varying(30) NOT NULL,
    role_key character varying(100) NOT NULL,
    role_sort integer NOT NULL,
    data_scope character(1) DEFAULT '1'::bpchar,
    menu_check_strictly boolean DEFAULT true,
    dept_check_strictly boolean DEFAULT true,
    status character(1) NOT NULL,
    del_flag character(1) DEFAULT '0'::bpchar,
    create_dept bigint,
    create_by bigint,
    create_time timestamp(6) without time zone,
    update_by bigint,
    update_time timestamp(6) without time zone,
    remark character varying(500) DEFAULT NULL::character varying
);


--
-- Name: sys_role_dept; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sys_role_dept (
    role_id bigint NOT NULL,
    dept_id bigint NOT NULL
);


--
-- Name: sys_role_menu; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sys_role_menu (
    role_id bigint NOT NULL,
    menu_id bigint NOT NULL
);


--
-- Name: sys_social; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sys_social (
    id bigint NOT NULL,
    user_id bigint NOT NULL,
    tenant_id character varying(20) DEFAULT '000000'::character varying,
    auth_id character varying(255) NOT NULL,
    source character varying(255) NOT NULL,
    open_id character varying(255) DEFAULT NULL::character varying,
    user_name character varying(30) NOT NULL,
    nick_name character varying(30) DEFAULT ''::character varying,
    email character varying(255) DEFAULT ''::character varying,
    avatar character varying(500) DEFAULT ''::character varying,
    access_token character varying(2000) NOT NULL,
    expire_in bigint,
    refresh_token character varying(2000) DEFAULT NULL::character varying,
    access_code character varying(255) DEFAULT NULL::character varying,
    union_id character varying(255) DEFAULT NULL::character varying,
    scope character varying(255) DEFAULT NULL::character varying,
    token_type character varying(255) DEFAULT NULL::character varying,
    id_token character varying(2000) DEFAULT NULL::character varying,
    mac_algorithm character varying(255) DEFAULT NULL::character varying,
    mac_key character varying(255) DEFAULT NULL::character varying,
    code character varying(255) DEFAULT NULL::character varying,
    oauth_token character varying(255) DEFAULT NULL::character varying,
    oauth_token_secret character varying(255) DEFAULT NULL::character varying,
    create_dept bigint,
    create_by bigint,
    create_time timestamp(6) without time zone,
    update_by bigint,
    update_time timestamp(6) without time zone,
    del_flag character(1) DEFAULT '0'::bpchar
);


--
-- Name: sys_tenant; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sys_tenant (
    id bigint NOT NULL,
    tenant_id character varying(20) NOT NULL,
    contact_user_name character varying(20) DEFAULT NULL::character varying,
    contact_phone character varying(20) DEFAULT NULL::character varying,
    company_name character varying(30) DEFAULT NULL::character varying,
    license_number character varying(30) DEFAULT NULL::character varying,
    address character varying(200) DEFAULT NULL::character varying,
    intro character varying(200) DEFAULT NULL::character varying,
    domain character varying(200) DEFAULT NULL::character varying,
    remark character varying(200) DEFAULT NULL::character varying,
    package_id bigint,
    expire_time timestamp(6) without time zone,
    account_count integer DEFAULT '-1'::integer,
    status character(1) DEFAULT '0'::bpchar,
    del_flag character(1) DEFAULT '0'::bpchar,
    create_dept bigint,
    create_by bigint,
    create_time timestamp(6) without time zone,
    update_by bigint,
    update_time timestamp(6) without time zone,
    responsibility_enabled character(1) DEFAULT '1'::bpchar
);


--
-- Name: sys_tenant_package; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sys_tenant_package (
    package_id bigint NOT NULL,
    package_name character varying(20) DEFAULT ''::character varying,
    menu_ids character varying(3000) DEFAULT ''::character varying,
    remark character varying(200) DEFAULT ''::character varying,
    menu_check_strictly boolean DEFAULT true,
    status character(1) DEFAULT '0'::bpchar,
    del_flag character(1) DEFAULT '0'::bpchar,
    create_dept bigint,
    create_by bigint,
    create_time timestamp(6) without time zone,
    update_by bigint,
    update_time timestamp(6) without time zone
);


--
-- Name: sys_user; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sys_user (
    user_id bigint NOT NULL,
    tenant_id character varying(20) DEFAULT '000000'::character varying,
    dept_id bigint,
    user_name character varying(30) NOT NULL,
    nick_name character varying(30) NOT NULL,
    user_type character varying(10) DEFAULT 'sys_user'::character varying,
    email character varying(50) DEFAULT ''::character varying,
    phonenumber character varying(11) DEFAULT ''::character varying,
    sex character(1) DEFAULT '0'::bpchar,
    avatar bigint,
    password character varying(100) DEFAULT ''::character varying,
    status character(1) DEFAULT '0'::bpchar,
    del_flag character(1) DEFAULT '0'::bpchar,
    login_ip character varying(128) DEFAULT ''::character varying,
    login_date timestamp(6) without time zone,
    create_dept bigint,
    create_by bigint,
    create_time timestamp(6) without time zone,
    update_by bigint,
    update_time timestamp(6) without time zone,
    remark character varying(500) DEFAULT NULL::character varying,
    user_level character varying(10) DEFAULT NULL::character varying
);


--
-- Name: sys_user_post; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sys_user_post (
    user_id bigint NOT NULL,
    post_id bigint NOT NULL
);


--
-- Name: sys_user_role; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sys_user_role (
    user_id bigint NOT NULL,
    role_id bigint NOT NULL
);


--
-- Name: dcs_ai_config dcs_ai_config_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.dcs_ai_config
    ADD CONSTRAINT dcs_ai_config_pkey PRIMARY KEY (config_id);


--
-- Name: dcs_ai_credit_ledger dcs_ai_credit_ledger_pk; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.dcs_ai_credit_ledger
    ADD CONSTRAINT dcs_ai_credit_ledger_pk PRIMARY KEY (id);


--
-- Name: dcs_ai_credit_plan dcs_ai_credit_plan_code_uk; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.dcs_ai_credit_plan
    ADD CONSTRAINT dcs_ai_credit_plan_code_uk UNIQUE (plan_code);


--
-- Name: dcs_ai_credit_plan dcs_ai_credit_plan_pk; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.dcs_ai_credit_plan
    ADD CONSTRAINT dcs_ai_credit_plan_pk PRIMARY KEY (id);


--
-- Name: dcs_ai_credit_subscription dcs_ai_credit_subscription_pk; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.dcs_ai_credit_subscription
    ADD CONSTRAINT dcs_ai_credit_subscription_pk PRIMARY KEY (id);


--
-- Name: dcs_assistant_action dcs_assistant_action_pk; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.dcs_assistant_action
    ADD CONSTRAINT dcs_assistant_action_pk PRIMARY KEY (id);


--
-- Name: dcs_assistant_attachment dcs_assistant_attachment_pk; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.dcs_assistant_attachment
    ADD CONSTRAINT dcs_assistant_attachment_pk PRIMARY KEY (id);


--
-- Name: dcs_assistant_attachment dcs_assistant_attachment_uk; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.dcs_assistant_attachment
    ADD CONSTRAINT dcs_assistant_attachment_uk UNIQUE (tenant_id, thread_id, oss_id);


--
-- Name: dcs_assistant_config dcs_assistant_config_pk; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.dcs_assistant_config
    ADD CONSTRAINT dcs_assistant_config_pk PRIMARY KEY (tenant_id);


--
-- Name: dcs_assistant_conversation dcs_assistant_conversation_pk; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.dcs_assistant_conversation
    ADD CONSTRAINT dcs_assistant_conversation_pk PRIMARY KEY (id);


--
-- Name: dcs_assistant_feedback dcs_assistant_feedback_pk; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.dcs_assistant_feedback
    ADD CONSTRAINT dcs_assistant_feedback_pk PRIMARY KEY (id);


--
-- Name: dcs_assistant_memory dcs_assistant_memory_pk; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.dcs_assistant_memory
    ADD CONSTRAINT dcs_assistant_memory_pk PRIMARY KEY (id);


--
-- Name: dcs_assistant_message dcs_assistant_message_pk; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.dcs_assistant_message
    ADD CONSTRAINT dcs_assistant_message_pk PRIMARY KEY (id);


--
-- Name: dcs_assistant_run dcs_assistant_run_pk; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.dcs_assistant_run
    ADD CONSTRAINT dcs_assistant_run_pk PRIMARY KEY (id);


--
-- Name: dcs_assistant_user_quota dcs_assistant_user_quota_pk; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.dcs_assistant_user_quota
    ADD CONSTRAINT dcs_assistant_user_quota_pk PRIMARY KEY (id);


--
-- Name: dcs_file_guard_log dcs_file_guard_log_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.dcs_file_guard_log
    ADD CONSTRAINT dcs_file_guard_log_pkey PRIMARY KEY (log_id);


--
-- Name: dcs_file_review_config dcs_file_review_config_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.dcs_file_review_config
    ADD CONSTRAINT dcs_file_review_config_pkey PRIMARY KEY (config_id);


--
-- Name: dcs_file_review dcs_file_review_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.dcs_file_review
    ADD CONSTRAINT dcs_file_review_pkey PRIMARY KEY (review_id);


--
-- Name: dcs_tenant_billing_mode dcs_tenant_billing_mode_pk; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.dcs_tenant_billing_mode
    ADD CONSTRAINT dcs_tenant_billing_mode_pk PRIMARY KEY (tenant_id);


--
-- Name: dcs_word_fp_feedback dcs_word_fp_feedback_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.dcs_word_fp_feedback
    ADD CONSTRAINT dcs_word_fp_feedback_pkey PRIMARY KEY (feedback_id);


--
-- Name: dcs_ai_doc_domain pk_dcs_ai_doc_domain; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.dcs_ai_doc_domain
    ADD CONSTRAINT pk_dcs_ai_doc_domain PRIMARY KEY (domain_id);


--
-- Name: dcs_ai_model_capability pk_dcs_ai_model_capability; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.dcs_ai_model_capability
    ADD CONSTRAINT pk_dcs_ai_model_capability PRIMARY KEY (id);


--
-- Name: dcs_ai_model_health pk_dcs_ai_model_health; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.dcs_ai_model_health
    ADD CONSTRAINT pk_dcs_ai_model_health PRIMARY KEY (id);


--
-- Name: dcs_ai_model_price pk_dcs_ai_model_price; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.dcs_ai_model_price
    ADD CONSTRAINT pk_dcs_ai_model_price PRIMARY KEY (price_id);


--
-- Name: dcs_ai_provider_access pk_dcs_ai_provider_access; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.dcs_ai_provider_access
    ADD CONSTRAINT pk_dcs_ai_provider_access PRIMARY KEY (id);


--
-- Name: dcs_ai_usage pk_dcs_ai_usage; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.dcs_ai_usage
    ADD CONSTRAINT pk_dcs_ai_usage PRIMARY KEY (usage_id);


--
-- Name: dcs_assistant_announcement pk_dcs_assistant_announcement; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.dcs_assistant_announcement
    ADD CONSTRAINT pk_dcs_assistant_announcement PRIMARY KEY (id);


--
-- Name: dcs_assistant_favorite pk_dcs_assistant_favorite; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.dcs_assistant_favorite
    ADD CONSTRAINT pk_dcs_assistant_favorite PRIMARY KEY (id);


--
-- Name: dcs_assistant_model_setting pk_dcs_assistant_model_setting; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.dcs_assistant_model_setting
    ADD CONSTRAINT pk_dcs_assistant_model_setting PRIMARY KEY (id);


--
-- Name: dcs_audit pk_dcs_audit; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.dcs_audit
    ADD CONSTRAINT pk_dcs_audit PRIMARY KEY (id);


--
-- Name: dcs_captcha_log pk_dcs_captcha_log; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.dcs_captcha_log
    ADD CONSTRAINT pk_dcs_captcha_log PRIMARY KEY (log_id);


--
-- Name: dcs_notice pk_dcs_notice; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.dcs_notice
    ADD CONSTRAINT pk_dcs_notice PRIMARY KEY (id);


--
-- Name: dcs_profile_review pk_dcs_profile_review; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.dcs_profile_review
    ADD CONSTRAINT pk_dcs_profile_review PRIMARY KEY (review_id);


--
-- Name: dcs_risk_action pk_dcs_risk_action; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.dcs_risk_action
    ADD CONSTRAINT pk_dcs_risk_action PRIMARY KEY (action_id);


--
-- Name: dcs_risk_event pk_dcs_risk_event; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.dcs_risk_event
    ADD CONSTRAINT pk_dcs_risk_event PRIMARY KEY (event_id);


--
-- Name: dcs_risk_rule pk_dcs_risk_rule; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.dcs_risk_rule
    ADD CONSTRAINT pk_dcs_risk_rule PRIMARY KEY (rule_id);


--
-- Name: dcs_risk_whitelist pk_dcs_risk_whitelist; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.dcs_risk_whitelist
    ADD CONSTRAINT pk_dcs_risk_whitelist PRIMARY KEY (whitelist_id);


--
-- Name: dcs_sensitive_word pk_dcs_sensitive_word; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.dcs_sensitive_word
    ADD CONSTRAINT pk_dcs_sensitive_word PRIMARY KEY (word_id);


--
-- Name: dcs_service_charge_rule pk_dcs_service_charge_rule; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.dcs_service_charge_rule
    ADD CONSTRAINT pk_dcs_service_charge_rule PRIMARY KEY (rule_id);


--
-- Name: dcs_submission pk_dcs_submission; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.dcs_submission
    ADD CONSTRAINT pk_dcs_submission PRIMARY KEY (id);


--
-- Name: dcs_task pk_dcs_task; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.dcs_task
    ADD CONSTRAINT pk_dcs_task PRIMARY KEY (id);


--
-- Name: dcs_task_field pk_dcs_task_field; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.dcs_task_field
    ADD CONSTRAINT pk_dcs_task_field PRIMARY KEY (id);


--
-- Name: dcs_tenant_wallet pk_dcs_tenant_wallet; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.dcs_tenant_wallet
    ADD CONSTRAINT pk_dcs_tenant_wallet PRIMARY KEY (wallet_id);


--
-- Name: dcs_tenant_wallet_ledger pk_dcs_tenant_wallet_ledger; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.dcs_tenant_wallet_ledger
    ADD CONSTRAINT pk_dcs_tenant_wallet_ledger PRIMARY KEY (ledger_id);


--
-- Name: dcs_wallet_alert_state pk_dcs_wallet_alert_state; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.dcs_wallet_alert_state
    ADD CONSTRAINT pk_dcs_wallet_alert_state PRIMARY KEY (alert_id);


--
-- Name: dcs_wallet_payment_order pk_dcs_wallet_payment_order; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.dcs_wallet_payment_order
    ADD CONSTRAINT pk_dcs_wallet_payment_order PRIMARY KEY (order_id);


--
-- Name: profile_event pk_profile_event; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.profile_event
    ADD CONSTRAINT pk_profile_event PRIMARY KEY (event_id);


--
-- Name: profile_recalc_batch pk_profile_recalc_batch; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.profile_recalc_batch
    ADD CONSTRAINT pk_profile_recalc_batch PRIMARY KEY (batch_id);


--
-- Name: profile_score_dimension pk_profile_score_dimension; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.profile_score_dimension
    ADD CONSTRAINT pk_profile_score_dimension PRIMARY KEY (dimension_id);


--
-- Name: profile_score_rule pk_profile_score_rule; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.profile_score_rule
    ADD CONSTRAINT pk_profile_score_rule PRIMARY KEY (rule_id);


--
-- Name: profile_score_snapshot pk_profile_score_snapshot; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.profile_score_snapshot
    ADD CONSTRAINT pk_profile_score_snapshot PRIMARY KEY (snapshot_id);


--
-- Name: profile_subject pk_profile_subject; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.profile_subject
    ADD CONSTRAINT pk_profile_subject PRIMARY KEY (id);


--
-- Name: sys_social pk_sys_social; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sys_social
    ADD CONSTRAINT pk_sys_social PRIMARY KEY (id);


--
-- Name: sys_tenant pk_sys_tenant; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sys_tenant
    ADD CONSTRAINT pk_sys_tenant PRIMARY KEY (id);


--
-- Name: sys_tenant_package pk_sys_tenant_package; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sys_tenant_package
    ADD CONSTRAINT pk_sys_tenant_package PRIMARY KEY (package_id);


--
-- Name: sys_client sys_client_pk; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sys_client
    ADD CONSTRAINT sys_client_pk PRIMARY KEY (id);


--
-- Name: sys_config sys_config_pk; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sys_config
    ADD CONSTRAINT sys_config_pk PRIMARY KEY (config_id);


--
-- Name: sys_dept sys_dept_pk; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sys_dept
    ADD CONSTRAINT sys_dept_pk PRIMARY KEY (dept_id);


--
-- Name: sys_dict_data sys_dict_data_pk; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sys_dict_data
    ADD CONSTRAINT sys_dict_data_pk PRIMARY KEY (dict_code);


--
-- Name: sys_dict_type sys_dict_type_pk; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sys_dict_type
    ADD CONSTRAINT sys_dict_type_pk PRIMARY KEY (dict_id);


--
-- Name: sys_logininfor sys_logininfor_pk; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sys_logininfor
    ADD CONSTRAINT sys_logininfor_pk PRIMARY KEY (info_id);


--
-- Name: sys_menu sys_menu_pk; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sys_menu
    ADD CONSTRAINT sys_menu_pk PRIMARY KEY (menu_id);


--
-- Name: sys_notice sys_notice_pk; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sys_notice
    ADD CONSTRAINT sys_notice_pk PRIMARY KEY (notice_id);


--
-- Name: sys_oper_log sys_oper_log_pk; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sys_oper_log
    ADD CONSTRAINT sys_oper_log_pk PRIMARY KEY (oper_id);


--
-- Name: sys_oss_config sys_oss_config_pk; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sys_oss_config
    ADD CONSTRAINT sys_oss_config_pk PRIMARY KEY (oss_config_id);


--
-- Name: sys_oss sys_oss_pk; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sys_oss
    ADD CONSTRAINT sys_oss_pk PRIMARY KEY (oss_id);


--
-- Name: sys_post sys_post_pk; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sys_post
    ADD CONSTRAINT sys_post_pk PRIMARY KEY (post_id);


--
-- Name: sys_role_dept sys_role_dept_pk; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sys_role_dept
    ADD CONSTRAINT sys_role_dept_pk PRIMARY KEY (role_id, dept_id);


--
-- Name: sys_role_menu sys_role_menu_pk; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sys_role_menu
    ADD CONSTRAINT sys_role_menu_pk PRIMARY KEY (role_id, menu_id);


--
-- Name: sys_role sys_role_pk; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sys_role
    ADD CONSTRAINT sys_role_pk PRIMARY KEY (role_id);


--
-- Name: sys_user sys_user_pk; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sys_user
    ADD CONSTRAINT sys_user_pk PRIMARY KEY (user_id);


--
-- Name: sys_user_post sys_user_post_pk; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sys_user_post
    ADD CONSTRAINT sys_user_post_pk PRIMARY KEY (user_id, post_id);


--
-- Name: sys_user_role sys_user_role_pk; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sys_user_role
    ADD CONSTRAINT sys_user_role_pk PRIMARY KEY (user_id, role_id);


--
-- Name: dcs_ai_model_capability uk_dcs_ai_cap; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.dcs_ai_model_capability
    ADD CONSTRAINT uk_dcs_ai_cap UNIQUE (tenant_id, provider, model_pattern);


--
-- Name: dcs_assistant_action uk_dcs_asst_act_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.dcs_assistant_action
    ADD CONSTRAINT uk_dcs_asst_act_key UNIQUE (idempotency_key);


--
-- Name: dcs_assistant_favorite uk_dcs_asst_fav; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.dcs_assistant_favorite
    ADD CONSTRAINT uk_dcs_asst_fav UNIQUE (tenant_id, user_id, content);


--
-- Name: dcs_assistant_model_setting uk_dcs_asst_model_setting; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.dcs_assistant_model_setting
    ADD CONSTRAINT uk_dcs_asst_model_setting UNIQUE (tenant_id);


--
-- Name: dcs_assistant_run uk_dcs_asst_run_runid; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.dcs_assistant_run
    ADD CONSTRAINT uk_dcs_asst_run_runid UNIQUE (run_id);


--
-- Name: dcs_assistant_user_quota uk_dcs_asst_user_quota; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.dcs_assistant_user_quota
    ADD CONSTRAINT uk_dcs_asst_user_quota UNIQUE (tenant_id, user_id);


--
-- Name: profile_event uk_profile_event_uid; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.profile_event
    ADD CONSTRAINT uk_profile_event_uid UNIQUE (tenant_id, source, event_uid);


--
-- Name: profile_subject uk_profile_subject; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.profile_subject
    ADD CONSTRAINT uk_profile_subject UNIQUE (tenant_id, source, subject_type, subject_id);


--
-- Name: dcs_file_review_config uq_dcs_file_review_config_tenant; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.dcs_file_review_config
    ADD CONSTRAINT uq_dcs_file_review_config_tenant UNIQUE (tenant_id);


--
-- Name: dcs_file_review uq_dcs_file_review_oss; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.dcs_file_review
    ADD CONSTRAINT uq_dcs_file_review_oss UNIQUE (oss_id);


--
-- Name: idx_assist_att_thread; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_assist_att_thread ON public.dcs_assistant_attachment USING btree (tenant_id, thread_id);


--
-- Name: idx_assist_att_user; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_assist_att_user ON public.dcs_assistant_attachment USING btree (user_id);


--
-- Name: idx_captcha_token; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_captcha_token ON public.dcs_captcha_log USING btree (token);


--
-- Name: idx_captcha_user; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_captcha_user ON public.dcs_captcha_log USING btree (user_id, created_time);


--
-- Name: idx_credit_ledger_tenant; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_credit_ledger_tenant ON public.dcs_ai_credit_ledger USING btree (tenant_id, biz_type, create_time);


--
-- Name: idx_credit_ledger_tenant_user; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_credit_ledger_tenant_user ON public.dcs_ai_credit_ledger USING btree (tenant_id, create_by, create_time);


--
-- Name: idx_credit_sub_tenant; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_credit_sub_tenant ON public.dcs_ai_credit_subscription USING btree (tenant_id, status, end_time);


--
-- Name: idx_dcs_ai_config_tenant_enabled; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_dcs_ai_config_tenant_enabled ON public.dcs_ai_config USING btree (tenant_id, enabled);


--
-- Name: idx_dcs_ai_model_health_check_time; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_dcs_ai_model_health_check_time ON public.dcs_ai_model_health USING btree (check_time);


--
-- Name: idx_dcs_ai_model_price_lookup; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_dcs_ai_model_price_lookup ON public.dcs_ai_model_price USING btree (tenant_id, provider, model_name, enabled) WHERE (del_flag = '0'::bpchar);


--
-- Name: idx_dcs_ai_provider_access_tp; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_dcs_ai_provider_access_tp ON public.dcs_ai_provider_access USING btree (tenant_id, provider) WHERE (del_flag = '0'::bpchar);


--
-- Name: idx_dcs_ai_usage_caller_time; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_dcs_ai_usage_caller_time ON public.dcs_ai_usage USING btree (tenant_id, caller, create_time DESC);


--
-- Name: idx_dcs_ai_usage_config_time; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_dcs_ai_usage_config_time ON public.dcs_ai_usage USING btree (config_id, create_time DESC);


--
-- Name: idx_dcs_ai_usage_tenant_time; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_dcs_ai_usage_tenant_time ON public.dcs_ai_usage USING btree (tenant_id, create_time DESC);


--
-- Name: idx_dcs_assist_ann_tenant_active; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_dcs_assist_ann_tenant_active ON public.dcs_assistant_announcement USING btree (tenant_id, status, publish_time DESC);


--
-- Name: idx_dcs_asst_act_run; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_dcs_asst_act_run ON public.dcs_assistant_action USING btree (tenant_id, run_id);


--
-- Name: idx_dcs_asst_conv_list; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_dcs_asst_conv_list ON public.dcs_assistant_conversation USING btree (tenant_id, user_id, last_message_time DESC);


--
-- Name: idx_dcs_asst_fav_user; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_dcs_asst_fav_user ON public.dcs_assistant_favorite USING btree (tenant_id, user_id);


--
-- Name: idx_dcs_asst_fb_msg; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_dcs_asst_fb_msg ON public.dcs_assistant_feedback USING btree (tenant_id, message_id);


--
-- Name: idx_dcs_asst_mem_user; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_dcs_asst_mem_user ON public.dcs_assistant_memory USING btree (tenant_id, user_id);


--
-- Name: idx_dcs_asst_msg_conv; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_dcs_asst_msg_conv ON public.dcs_assistant_message USING btree (tenant_id, conversation_id, id);


--
-- Name: idx_dcs_asst_run_thread; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_dcs_asst_run_thread ON public.dcs_assistant_run USING btree (tenant_id, thread_id);


--
-- Name: idx_dcs_asst_run_user_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_dcs_asst_run_user_status ON public.dcs_assistant_run USING btree (tenant_id, user_id, status);


--
-- Name: idx_dcs_audit_submission; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_dcs_audit_submission ON public.dcs_audit USING btree (submission_id);


--
-- Name: idx_dcs_audit_task; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_dcs_audit_task ON public.dcs_audit USING btree (task_id);


--
-- Name: idx_dcs_file_guard_log_disposition; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_dcs_file_guard_log_disposition ON public.dcs_file_guard_log USING btree (disposition);


--
-- Name: idx_dcs_file_guard_log_time; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_dcs_file_guard_log_time ON public.dcs_file_guard_log USING btree (create_time);


--
-- Name: idx_dcs_file_review_reviewer; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_dcs_file_review_reviewer ON public.dcs_file_review USING btree (tenant_id, reviewer_id, review_time DESC);


--
-- Name: idx_dcs_file_review_tenant_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_dcs_file_review_tenant_status ON public.dcs_file_review USING btree (tenant_id, status, create_time DESC);


--
-- Name: idx_dcs_notice_user; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_dcs_notice_user ON public.dcs_notice USING btree (tenant_id, user_id, read_flag);


--
-- Name: idx_dcs_profile_review_queue; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_dcs_profile_review_queue ON public.dcs_profile_review USING btree (tenant_id, status, create_time DESC);


--
-- Name: idx_dcs_risk_action_queue; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_dcs_risk_action_queue ON public.dcs_risk_action USING btree (tenant_id, status, start_time DESC);


--
-- Name: idx_dcs_sensitive_word_enabled; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_dcs_sensitive_word_enabled ON public.dcs_sensitive_word USING btree (tenant_id, enabled, action) WHERE (del_flag = '0'::bpchar);


--
-- Name: idx_dcs_submission_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_dcs_submission_status ON public.dcs_submission USING btree (tenant_id, status);


--
-- Name: idx_dcs_submission_task_user; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_dcs_submission_task_user ON public.dcs_submission USING btree (task_id, submitter_user_id, version);


--
-- Name: idx_dcs_task_field_task; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_dcs_task_field_task ON public.dcs_task_field USING btree (task_id);


--
-- Name: idx_dcs_task_publish_time; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_dcs_task_publish_time ON public.dcs_task USING btree (publish_time);


--
-- Name: idx_dcs_task_publisher; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_dcs_task_publisher ON public.dcs_task USING btree (tenant_id, publisher_user_id);


--
-- Name: idx_dcs_task_tenant_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_dcs_task_tenant_status ON public.dcs_task USING btree (tenant_id, status);


--
-- Name: idx_dcs_word_fp_detail; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_dcs_word_fp_detail ON public.dcs_word_fp_feedback USING btree (detail_id);


--
-- Name: idx_dcs_word_fp_time; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_dcs_word_fp_time ON public.dcs_word_fp_feedback USING btree (create_time);


--
-- Name: idx_profile_event_query; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_profile_event_query ON public.profile_event USING btree (tenant_id, source, subject_type, subject_id, event_type, occurred_time DESC);


--
-- Name: idx_profile_event_time; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_profile_event_time ON public.profile_event USING btree (occurred_time);


--
-- Name: idx_profile_event_name; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_profile_event_name ON public.profile_event USING btree (tenant_id, source, event_name, occurred_time DESC);


--
-- Name: idx_profile_event_session; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_profile_event_session ON public.profile_event USING btree (tenant_id, source, session_id, occurred_time DESC) WHERE (session_id IS NOT NULL);


--
-- Name: idx_profile_identity_link_subject; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_profile_identity_link_subject ON public.profile_identity_link USING btree (tenant_id, source, subject_type, subject_id);


--
-- Name: idx_profile_identity_merge_from; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_profile_identity_merge_from ON public.profile_identity_merge USING btree (tenant_id, source, subject_type, from_subject_id);


--
-- Name: idx_profile_protocol_current; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_profile_protocol_current ON public.profile_protocol USING btree (tenant_id, source, status);


--
-- Name: uk_profile_protocol_published; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_profile_protocol_published ON public.profile_protocol USING btree (tenant_id, source) WHERE ((status = 'PUBLISHED'::text) AND (del_flag = '0'::bpchar));


--
-- Name: idx_profile_schema_source; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_profile_schema_source ON public.profile_schema_registry USING btree (tenant_id, source, event_name);


--
-- Name: idx_profile_snap_subject; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_profile_snap_subject ON public.profile_score_snapshot USING btree (tenant_id, source, subject_id, calc_time DESC);


--
-- Name: idx_profile_snap_latest; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_profile_snap_latest ON public.profile_score_snapshot USING btree (tenant_id, source, subject_type, subject_id, calc_time DESC, snapshot_id DESC);


--
-- Name: idx_profile_snap_rank; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_profile_snap_rank ON public.profile_score_snapshot USING btree (tenant_id, source, total_score DESC);


--
-- Name: idx_profile_rule_source; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_profile_rule_source ON public.profile_score_rule USING btree (tenant_id, source, status, create_time DESC);


--
-- Name: uk_profile_rule_published; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_profile_rule_published ON public.profile_score_rule USING btree (tenant_id, source) WHERE ((status = 'PUBLISHED'::text) AND (del_flag = '0'::bpchar));


--
-- Name: uk_profile_rule_draft; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_profile_rule_draft ON public.profile_score_rule USING btree (tenant_id, source) WHERE ((status = 'DRAFT'::text) AND (del_flag = '0'::bpchar));


--
-- Name: idx_risk_event_ip_time; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_risk_event_ip_time ON public.dcs_risk_event USING btree (ip, created_time);


--
-- Name: idx_risk_event_type_time; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_risk_event_type_time ON public.dcs_risk_event USING btree (event_type, created_time);


--
-- Name: idx_risk_event_user_time; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_risk_event_user_time ON public.dcs_risk_event USING btree (user_id, created_time);


--
-- Name: idx_risk_wl_type_value; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_risk_wl_type_value ON public.dcs_risk_whitelist USING btree (list_type, value);


--
-- Name: idx_sys_dept_ancestors_trgm; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_sys_dept_ancestors_trgm ON public.sys_dept USING gin (ancestors public.gin_trgm_ops);


--
-- Name: idx_sys_logininfor_lt; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_sys_logininfor_lt ON public.sys_logininfor USING btree (login_time);


--
-- Name: idx_sys_logininfor_s; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_sys_logininfor_s ON public.sys_logininfor USING btree (status);


--
-- Name: idx_sys_oper_log_bt; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_sys_oper_log_bt ON public.sys_oper_log USING btree (business_type);


--
-- Name: idx_sys_oper_log_ot; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_sys_oper_log_ot ON public.sys_oper_log USING btree (oper_time);


--
-- Name: idx_sys_oper_log_s; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_sys_oper_log_s ON public.sys_oper_log USING btree (status);


--
-- Name: idx_sys_user_phonenumber; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_sys_user_phonenumber ON public.sys_user USING btree (phonenumber);


--
-- Name: idx_sys_user_username; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_sys_user_username ON public.sys_user USING btree (user_name);


--
-- Name: idx_tenant_wallet_tenant_type; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_tenant_wallet_tenant_type ON public.dcs_tenant_wallet USING btree (tenant_id, wallet_type, status) WHERE (del_flag = '0'::bpchar);


--
-- Name: idx_wallet_ledger_tenant_service; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_wallet_ledger_tenant_service ON public.dcs_tenant_wallet_ledger USING btree (tenant_id, service_code, create_time);


--
-- Name: idx_wallet_ledger_wallet_time; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_wallet_ledger_wallet_time ON public.dcs_tenant_wallet_ledger USING btree (wallet_id, create_time);


--
-- Name: idx_wallet_payment_order_tenant_time; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_wallet_payment_order_tenant_time ON public.dcs_wallet_payment_order USING btree (tenant_id, create_time);


--
-- Name: sys_dict_type_index1; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX sys_dict_type_index1 ON public.sys_dict_type USING btree (tenant_id, dict_type);


--
-- Name: uk_credit_ledger_idem; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_credit_ledger_idem ON public.dcs_ai_credit_ledger USING btree (idempotency_key) WHERE (idempotency_key IS NOT NULL);


--
-- Name: uk_credit_sub_active; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_credit_sub_active ON public.dcs_ai_credit_subscription USING btree (tenant_id) WHERE (status = '1'::bpchar);


--
-- Name: uk_dcs_ai_config_tenant_default; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_dcs_ai_config_tenant_default ON public.dcs_ai_config USING btree (tenant_id, use_type) WHERE ((is_default = '1'::bpchar) AND (del_flag = '0'::bpchar));


--
-- Name: uk_dcs_ai_config_tenant_model; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_dcs_ai_config_tenant_model ON public.dcs_ai_config USING btree (tenant_id, provider, model_name) WHERE (del_flag = '0'::bpchar);


--
-- Name: uk_dcs_ai_doc_domain; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_dcs_ai_doc_domain ON public.dcs_ai_doc_domain USING btree (tenant_id, domain) WHERE (del_flag = '0'::bpchar);


--
-- Name: uk_dcs_ai_model_health_tenant_config; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_dcs_ai_model_health_tenant_config ON public.dcs_ai_model_health USING btree (tenant_id, config_id);


--
-- Name: uk_dcs_ai_model_price_model; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_dcs_ai_model_price_model ON public.dcs_ai_model_price USING btree (tenant_id, provider, model_name) WHERE (del_flag = '0'::bpchar);


--
-- Name: uk_dcs_asst_conv_thread; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_dcs_asst_conv_thread ON public.dcs_assistant_conversation USING btree (tenant_id, thread_id);


--
-- Name: uk_dcs_notice_dedup; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_dcs_notice_dedup ON public.dcs_notice USING btree (tenant_id, user_id, dedup_key) WHERE (dedup_key IS NOT NULL);


--
-- Name: uk_dcs_profile_review_pending_user; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_dcs_profile_review_pending_user ON public.dcs_profile_review USING btree (tenant_id, user_id) WHERE ((status)::text = 'PENDING'::text);


--
-- Name: uk_dcs_risk_action_active_user; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_dcs_risk_action_active_user ON public.dcs_risk_action USING btree (tenant_id, user_id, action_type) WHERE ((status)::text = 'ACTIVE'::text);


--
-- Name: uk_dcs_risk_event_idempotency; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_dcs_risk_event_idempotency ON public.dcs_risk_event USING btree (tenant_id, idempotency_key) WHERE (idempotency_key IS NOT NULL);


--
-- Name: uk_dcs_risk_global_rule; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_dcs_risk_global_rule ON public.dcs_risk_rule USING btree (tenant_id) WHERE (((rule_scope)::text = 'GLOBAL'::text) AND (enabled = '1'::bpchar));


--
-- Name: uk_dcs_risk_rule_event; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_dcs_risk_rule_event ON public.dcs_risk_rule USING btree (tenant_id, event_type) WHERE ((rule_scope)::text = 'EVENT'::text);


--
-- Name: uk_dcs_risk_whitelist_item; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_dcs_risk_whitelist_item ON public.dcs_risk_whitelist USING btree (tenant_id, list_type, value);


--
-- Name: uk_dcs_sensitive_word_text; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_dcs_sensitive_word_text ON public.dcs_sensitive_word USING btree (tenant_id, word_text) WHERE (del_flag = '0'::bpchar);


--
-- Name: uk_dcs_submission_task_ver; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_dcs_submission_task_ver ON public.dcs_submission USING btree (tenant_id, task_id, submitter_user_id, version);


--
-- Name: uk_dcs_task_field_code; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_dcs_task_field_code ON public.dcs_task_field USING btree (task_id, field_code);


--
-- Name: uk_dcs_word_fp_detail_word; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_dcs_word_fp_detail_word ON public.dcs_word_fp_feedback USING btree (detail_id, word_text);


--
-- Name: uk_profile_recalc_running; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_profile_recalc_running ON public.profile_recalc_batch USING btree (tenant_id, source) WHERE ((status)::text = 'RUNNING'::text);


--
-- Name: uk_profile_score_dimension; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_profile_score_dimension ON public.profile_score_dimension USING btree (tenant_id, source, dim_code) WHERE (del_flag = '0'::bpchar);


--
-- Name: uk_service_charge_rule_tenant_code; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_service_charge_rule_tenant_code ON public.dcs_service_charge_rule USING btree (tenant_id, service_code) WHERE (del_flag = '0'::bpchar);


--
-- Name: uk_sys_config_tenant_key; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_sys_config_tenant_key ON public.sys_config USING btree (tenant_id, config_key);


--
-- Name: uk_sys_user_tenant_email_ci; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_sys_user_tenant_email_ci ON public.sys_user USING btree (tenant_id, lower((email)::text)) WHERE ((del_flag = '0'::bpchar) AND (email IS NOT NULL) AND ((email)::text <> ''::text));


--
-- Name: uk_sys_user_tenant_phone; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_sys_user_tenant_phone ON public.sys_user USING btree (tenant_id, phonenumber) WHERE ((del_flag = '0'::bpchar) AND (phonenumber IS NOT NULL) AND ((phonenumber)::text <> ''::text));


--
-- Name: uk_tenant_wallet_main; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_tenant_wallet_main ON public.dcs_tenant_wallet USING btree (tenant_id, wallet_type) WHERE (((wallet_type)::text = 'MAIN'::text) AND (del_flag = '0'::bpchar));


--
-- Name: uk_wallet_alert_state_wallet_type; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_wallet_alert_state_wallet_type ON public.dcs_wallet_alert_state USING btree (wallet_id, alert_type) WHERE (del_flag = '0'::bpchar);


--
-- Name: uk_wallet_ledger_idempotency; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_wallet_ledger_idempotency ON public.dcs_tenant_wallet_ledger USING btree (idempotency_key) WHERE (del_flag = '0'::bpchar);


--
-- Name: uk_wallet_payment_order_no; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_wallet_payment_order_no ON public.dcs_wallet_payment_order USING btree (order_no) WHERE (del_flag = '0'::bpchar);


--
-- PostgreSQL database dump complete
--

--
-- PostgreSQL database dump
--


-- Dumped from database version 17.10 (Debian 17.10-1.pgdg13+1)
-- Dumped by pg_dump version 17.10

SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
SET transaction_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SELECT pg_catalog.set_config('search_path', '', false);
SET check_function_bodies = false;
SET xmloption = content;
SET client_min_messages = warning;
SET row_security = off;

--
-- Name: agentscope; Type: SCHEMA; Schema: -; Owner: -
--

CREATE SCHEMA agentscope;


SET default_tablespace = '';

SET default_table_access_method = heap;

--
-- Name: agentscope_sessions; Type: TABLE; Schema: agentscope; Owner: -
--

CREATE TABLE agentscope.agentscope_sessions (
    session_id character varying(255) NOT NULL,
    state_key character varying(255) NOT NULL,
    item_index integer DEFAULT 0 NOT NULL,
    state_data text NOT NULL,
    version bigint DEFAULT 0 NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP
);


--
-- Name: agentscope_snapshots; Type: TABLE; Schema: agentscope; Owner: -
--

CREATE TABLE agentscope.agentscope_snapshots (
    snapshot_id character varying(512) NOT NULL,
    data bytea NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP
);


--
-- Name: agentscope_store; Type: TABLE; Schema: agentscope; Owner: -
--

CREATE TABLE agentscope.agentscope_store (
    namespace_path character varying(2048) NOT NULL,
    item_key character varying(255) NOT NULL,
    value_json text NOT NULL,
    version bigint NOT NULL,
    updated_at bigint NOT NULL
);


--
-- Name: dcs_p0_probe_audit; Type: TABLE; Schema: agentscope; Owner: -
--

CREATE TABLE agentscope.dcs_p0_probe_audit (
    id bigint NOT NULL,
    event character varying(32) NOT NULL,
    idempotency_key character varying(128),
    detail text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    user_key character varying(64)
);


--
-- Name: dcs_p0_probe_audit_id_seq; Type: SEQUENCE; Schema: agentscope; Owner: -
--

CREATE SEQUENCE agentscope.dcs_p0_probe_audit_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: dcs_p0_probe_audit_id_seq; Type: SEQUENCE OWNED BY; Schema: agentscope; Owner: -
--

ALTER SEQUENCE agentscope.dcs_p0_probe_audit_id_seq OWNED BY agentscope.dcs_p0_probe_audit.id;


--
-- Name: dcs_p0_probe_ledger; Type: TABLE; Schema: agentscope; Owner: -
--

CREATE TABLE agentscope.dcs_p0_probe_ledger (
    id bigint NOT NULL,
    idempotency_key character varying(128) NOT NULL,
    user_key character varying(64) NOT NULL,
    run_id character varying(64) NOT NULL,
    seq integer NOT NULL,
    amount numeric(14,6) NOT NULL,
    direction character varying(3) NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: dcs_p0_probe_ledger_id_seq; Type: SEQUENCE; Schema: agentscope; Owner: -
--

CREATE SEQUENCE agentscope.dcs_p0_probe_ledger_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: dcs_p0_probe_ledger_id_seq; Type: SEQUENCE OWNED BY; Schema: agentscope; Owner: -
--

ALTER SEQUENCE agentscope.dcs_p0_probe_ledger_id_seq OWNED BY agentscope.dcs_p0_probe_ledger.id;


--
-- Name: dcs_p0_probe_run_registry; Type: TABLE; Schema: agentscope; Owner: -
--

CREATE TABLE agentscope.dcs_p0_probe_run_registry (
    run_id character varying(64) NOT NULL,
    user_key character varying(64) NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: dcs_p0_probe_usage; Type: TABLE; Schema: agentscope; Owner: -
--

CREATE TABLE agentscope.dcs_p0_probe_usage (
    id bigint NOT NULL,
    idempotency_key character varying(128) NOT NULL,
    user_key character varying(64) NOT NULL,
    run_id character varying(64) NOT NULL,
    seq integer NOT NULL,
    tokens_in integer NOT NULL,
    tokens_out integer NOT NULL,
    unit_price numeric(14,8) NOT NULL,
    amount numeric(14,6) NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: dcs_p0_probe_usage_id_seq; Type: SEQUENCE; Schema: agentscope; Owner: -
--

CREATE SEQUENCE agentscope.dcs_p0_probe_usage_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: dcs_p0_probe_usage_id_seq; Type: SEQUENCE OWNED BY; Schema: agentscope; Owner: -
--

ALTER SEQUENCE agentscope.dcs_p0_probe_usage_id_seq OWNED BY agentscope.dcs_p0_probe_usage.id;


--
-- Name: dcs_p0_probe_wallet; Type: TABLE; Schema: agentscope; Owner: -
--

CREATE TABLE agentscope.dcs_p0_probe_wallet (
    user_key character varying(64) NOT NULL,
    balance numeric(14,6) NOT NULL
);


--
-- Name: dcs_p0_probe_audit id; Type: DEFAULT; Schema: agentscope; Owner: -
--

ALTER TABLE ONLY agentscope.dcs_p0_probe_audit ALTER COLUMN id SET DEFAULT nextval('agentscope.dcs_p0_probe_audit_id_seq'::regclass);


--
-- Name: dcs_p0_probe_ledger id; Type: DEFAULT; Schema: agentscope; Owner: -
--

ALTER TABLE ONLY agentscope.dcs_p0_probe_ledger ALTER COLUMN id SET DEFAULT nextval('agentscope.dcs_p0_probe_ledger_id_seq'::regclass);


--
-- Name: dcs_p0_probe_usage id; Type: DEFAULT; Schema: agentscope; Owner: -
--

ALTER TABLE ONLY agentscope.dcs_p0_probe_usage ALTER COLUMN id SET DEFAULT nextval('agentscope.dcs_p0_probe_usage_id_seq'::regclass);


--
-- Name: agentscope_sessions agentscope_sessions_pkey; Type: CONSTRAINT; Schema: agentscope; Owner: -
--

ALTER TABLE ONLY agentscope.agentscope_sessions
    ADD CONSTRAINT agentscope_sessions_pkey PRIMARY KEY (session_id, state_key, item_index);


--
-- Name: agentscope_snapshots agentscope_snapshots_pkey; Type: CONSTRAINT; Schema: agentscope; Owner: -
--

ALTER TABLE ONLY agentscope.agentscope_snapshots
    ADD CONSTRAINT agentscope_snapshots_pkey PRIMARY KEY (snapshot_id);


--
-- Name: agentscope_store agentscope_store_pkey; Type: CONSTRAINT; Schema: agentscope; Owner: -
--

ALTER TABLE ONLY agentscope.agentscope_store
    ADD CONSTRAINT agentscope_store_pkey PRIMARY KEY (namespace_path, item_key);


--
-- Name: dcs_p0_probe_audit dcs_p0_probe_audit_pkey; Type: CONSTRAINT; Schema: agentscope; Owner: -
--

ALTER TABLE ONLY agentscope.dcs_p0_probe_audit
    ADD CONSTRAINT dcs_p0_probe_audit_pkey PRIMARY KEY (id);


--
-- Name: dcs_p0_probe_ledger dcs_p0_probe_ledger_idempotency_key_key; Type: CONSTRAINT; Schema: agentscope; Owner: -
--

ALTER TABLE ONLY agentscope.dcs_p0_probe_ledger
    ADD CONSTRAINT dcs_p0_probe_ledger_idempotency_key_key UNIQUE (idempotency_key);


--
-- Name: dcs_p0_probe_ledger dcs_p0_probe_ledger_pkey; Type: CONSTRAINT; Schema: agentscope; Owner: -
--

ALTER TABLE ONLY agentscope.dcs_p0_probe_ledger
    ADD CONSTRAINT dcs_p0_probe_ledger_pkey PRIMARY KEY (id);


--
-- Name: dcs_p0_probe_run_registry dcs_p0_probe_run_registry_pkey; Type: CONSTRAINT; Schema: agentscope; Owner: -
--

ALTER TABLE ONLY agentscope.dcs_p0_probe_run_registry
    ADD CONSTRAINT dcs_p0_probe_run_registry_pkey PRIMARY KEY (run_id);


--
-- Name: dcs_p0_probe_usage dcs_p0_probe_usage_idempotency_key_key; Type: CONSTRAINT; Schema: agentscope; Owner: -
--

ALTER TABLE ONLY agentscope.dcs_p0_probe_usage
    ADD CONSTRAINT dcs_p0_probe_usage_idempotency_key_key UNIQUE (idempotency_key);


--
-- Name: dcs_p0_probe_usage dcs_p0_probe_usage_pkey; Type: CONSTRAINT; Schema: agentscope; Owner: -
--

ALTER TABLE ONLY agentscope.dcs_p0_probe_usage
    ADD CONSTRAINT dcs_p0_probe_usage_pkey PRIMARY KEY (id);


--
-- Name: dcs_p0_probe_wallet dcs_p0_probe_wallet_pkey; Type: CONSTRAINT; Schema: agentscope; Owner: -
--

ALTER TABLE ONLY agentscope.dcs_p0_probe_wallet
    ADD CONSTRAINT dcs_p0_probe_wallet_pkey PRIMARY KEY (user_key);


--
-- Name: agentscope_sessions_session_idx; Type: INDEX; Schema: agentscope; Owner: -
--

CREATE INDEX agentscope_sessions_session_idx ON agentscope.agentscope_sessions USING btree (session_id);


--
-- Name: agentscope_store_namespace_idx; Type: INDEX; Schema: agentscope; Owner: -
--

CREATE INDEX agentscope_store_namespace_idx ON agentscope.agentscope_store USING btree (namespace_path);


--
-- PostgreSQL database dump complete
--


