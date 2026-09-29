-- Run against local SQL Server with a developer account allowed to create databases.
IF DB_ID(N'AMHS_DEV') IS NULL CREATE DATABASE AMHS_DEV;
GO
