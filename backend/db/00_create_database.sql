-- =====================================================================
-- Bangmod Smart Attendance (CP26MS3) — 00: create database
-- Run as a superuser on the PostgreSQL server of each environment
-- (UAT namespace and Prod namespace each have their own PostgreSQL).
--   psql -U postgres -f 00_create_database.sql
-- =====================================================================

CREATE DATABASE bangmod_attendance
  WITH ENCODING 'UTF8'
       TEMPLATE template0;

-- Every timestamptz is shown in Thai time; the API returns Thai local time with no zone.
ALTER DATABASE bangmod_attendance SET timezone TO 'Asia/Bangkok';

-- Application login used by Spring Boot (change the password per environment, keep it in a K3s Secret).
-- CREATE ROLE attendance_app LOGIN PASSWORD 'change-me';
-- GRANT CONNECT ON DATABASE bangmod_attendance TO attendance_app;
