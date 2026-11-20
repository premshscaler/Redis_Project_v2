import streamlit as st
import requests

API_URL = "http://localhost:8080/leaderboard"

st.set_page_config(
    page_title="Mini Redis Multi-Type Cache & Leaderboard",
    page_icon="⚡",
    layout="centered",
)

st.title("⚡ Mini Multi-Type Cache Engine (Redis-Inspired)")
st.caption(
    "Powered by Skip Lists, LRU Eviction, TTL Expiry, and Multi-Type Backends "
    "(Sorted Set, String, List, Hash)."
)

# ---------------- Sidebar ----------------
st.sidebar.header("Cache & ZSET Operations")
action = st.sidebar.selectbox(
    "Operation Type",
    [
        "ZADD (Score/Leaderboard)",
        "EXPIRE (TTL)",
        "SET (String Cache)",
        "LPUSH (List Feed)",
        "HSET (Hash Profile)",
    ],
)
pid = st.sidebar.text_input("Player / Key ID", "Valkyrie")

if action == "ZADD (Score/Leaderboard)":
    score = st.sidebar.number_input("Score", 0, 5000, 3200)
    if st.sidebar.button("Execute ZADD"):
        try:
            res = requests.post(f"{API_URL}?action=zadd&id={pid}&score={score}")
            st.sidebar.success(res.json())
        except Exception as e:
            st.sidebar.error(f"Backend offline: {e}")

elif action == "EXPIRE (TTL)":
    seconds = st.sidebar.number_input("TTL Seconds", 1, 3600, 60)
    if st.sidebar.button("Set EXPIRE"):
        try:
            res = requests.post(f"{API_URL}?action=expire&id={pid}&seconds={seconds}")
            st.sidebar.success(res.json())
        except Exception as e:
            st.sidebar.error(f"Backend offline: {e}")

elif action == "SET (String Cache)":
    val = st.sidebar.text_input("String Value", "Active Session Token")
    if st.sidebar.button("Execute SET"):
        try:
            res = requests.post(f"{API_URL}?action=string&id={pid}&val={val}")
            st.sidebar.success(res.json())
        except Exception as e:
            st.sidebar.error(f"Backend offline: {e}")

elif action == "LPUSH (List Feed)":
    val = st.sidebar.text_input("Match Feed Item", "Match Won: +25 Elo")
    if st.sidebar.button("Execute LPUSH"):
        try:
            res = requests.post(f"{API_URL}?action=lpush&id={pid}&val={val}")
            st.sidebar.success(res.json())
        except Exception as e:
            st.sidebar.error(f"Backend offline: {e}")

elif action == "HSET (Hash Profile)":
    field = st.sidebar.text_input("Hash Field", "region")
    val = st.sidebar.text_input("Hash Value", "EU-West")
    if st.sidebar.button("Execute HSET"):
        try:
            res = requests.post(f"{API_URL}?action=hset&id={pid}&field={field}&val={val}")
            st.sidebar.success(res.json())
        except Exception as e:
            st.sidebar.error(f"Backend offline: {e}")

st.sidebar.divider()
st.sidebar.header("Cache Metrics (INFO)")
if st.sidebar.button("Fetch Cache INFO"):
    try:
        stats = requests.get(f"{API_URL}?stats=true").json()
        st.sidebar.json(stats)
    except Exception as e:
        st.sidebar.error(f"Backend offline: {e}")

# ---------------- Main area ----------------
if st.button("🔄 Refresh Dashboards"):
    st.rerun()

try:
    data = requests.get(API_URL).json()
except Exception:
    data = {"Gold": [], "Silver": [], "Bronze": []}

tab_gold, tab_silver, tab_bronze = st.tabs(
    ["🟡 Gold (>=3000)", "⚪ Silver (1500-2999)", "🥉 Bronze (<1500)"]
)


def render_tier_table(tier_list):
    if not tier_list:
        st.info("No entries in this tier cache.")
        return
    for item in tier_list:
        cols = st.columns([1, 3, 2])
        if item['rank'] == 1:
            medal = "🥇"
        elif item['rank'] == 2:
            medal = "🥈"
        elif item['rank'] == 3:
            medal = "🥉"
        else:
            medal = "🔸"
        cols[0].markdown(f"**{medal} #{item['rank']}**")
        cols[1].text(item['playerId'])
        cols[2].metric("Score", item['score'])


with tab_gold:
    render_tier_table(data.get("Gold", []))
with tab_silver:
    render_tier_table(data.get("Silver", []))
with tab_bronze:
    render_tier_table(data.get("Bronze", []))

st.divider()
st.subheader("🔍 Key Inspector & Multi-Type Data Retrieval")
search_id = st.text_input("Lookup Key / Player ID", "Valkyrie")
if st.button("Inspect Key"):
    try:
        res = requests.get(f"{API_URL}?id={search_id}").json()
        if "error" in res:
            st.warning(res["error"])
        else:
            st.json(res)
    except Exception as e:
        st.error(f"Backend offline: {e}")