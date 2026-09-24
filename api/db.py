"""Database connections for Neon (read) and AKTIV MSSQL (write)."""
from __future__ import annotations

import contextlib

import psycopg2
import pymssql

from config import mssql_config, neon_url


@contextlib.contextmanager
def neon_conn():
    conn = psycopg2.connect(neon_url())
    try:
        yield conn
    finally:
        conn.close()


@contextlib.contextmanager
def mssql_conn():
    cfg = mssql_config()
    conn = pymssql.connect(
        server=cfg["server"],
        port=cfg["port"],
        user=cfg["user"],
        password=cfg["password"],
        database=cfg["database"],
        tds_version=cfg["tds_version"],
        autocommit=False,
    )
    try:
        yield conn
    finally:
        conn.close()


def fetch_all(cur, sql: str, params: tuple = ()) -> list[dict]:
    cur.execute(sql, params)
    cols = [d[0] for d in cur.description]
    return [dict(zip(cols, row)) for row in cur.fetchall()]


def fetch_one(cur, sql: str, params: tuple = ()) -> dict | None:
    rows = fetch_all(cur, sql, params)
    return rows[0] if rows else None
