import sqlite3
import json
import asyncio
import aiohttp
import os
import sys
import urllib.request
import urllib.error

DB_FILE = "MasterUnifiedDB.db" # The merged DB we just created
OLLAMA_URL = "http://localhost:11434/api/generate"
MODEL_NAME = "llama3.2"  
MAX_CONCURRENT_REQUESTS = 10  # Ollama scales well natively

def check_and_merge_journal():
    journal_file = f"{DB_FILE}-journal"
    wal_file = f"{DB_FILE}-wal"
    if os.path.exists(journal_file) or os.path.exists(wal_file):
        print(f"[*] Found existing SQLite journal/wal file. Attempting to merge/recover...", flush=True)
        try:
            conn = sqlite3.connect(DB_FILE, timeout=60.0)
            cursor = conn.cursor()
            # SQLite automatically recovers the rollback journal on connection.
            cursor.execute("SELECT 1")
            # If it's a WAL, we can checkpoint it explicitly.
            cursor.execute("PRAGMA wal_checkpoint(TRUNCATE)")
            conn.commit()
            conn.close()
            print("[✓] Journal successfully merged into main database.", flush=True)
        except Exception as e:
            print(f"[!] Error recovering journal: {e}")

def test_ollama_connection():
    print("[.] Testing connection to local Ollama server (http://localhost:11434)...", flush=True)
    try:
        url = "http://localhost:11434/api/tags"
        req = urllib.request.Request(url)
        with urllib.request.urlopen(req, timeout=5) as response:
            res_content = response.read().decode("utf-8")
            data = json.loads(res_content)
            models = [m["name"] for m in data.get("models", [])]
            print(f"[✓] Successfully connected to Ollama!")
            print(f"[✓] Available models: {', '.join(models) if models else 'None installed!'}")
            
            # Warn if active model might be missing
            model_base = MODEL_NAME.split(':')[0]
            installed = any(m.startswith(model_base) for m in models)
            if not installed:
                print(f"\n[!] WARNING: Model '{MODEL_NAME}' may not be installed.")
                print(f"    Please run 'ollama run {MODEL_NAME}' in a separate terminal to download it first.")
            else:
                print(f"[✓] Verified '{MODEL_NAME}' is installed.")
                print(f"    (Tip: To max out GPU concurrency, start Ollama server with 'OLLAMA_NUM_PARALLEL=4 ollama serve')")
            
            return True
    except urllib.error.URLError as e:
        print("\n[!] FATAL ERROR: Cannot connect to Ollama.")
        print(f"    Details: {e.reason}")
        print("\n    Please make sure Ollama is installed and running!")
        print("    If you are on Windows, open the Start menu and launch 'Ollama'.")
        print("    Alternatively, open a new terminal and type:")
        print("        ollama serve\n")
        return False
    except Exception as e:
        print(f"\n[!] Unexpected error checking Ollama: {e}")
        return False

def fetch_pending_ingredients():
    conn = sqlite3.connect(DB_FILE, timeout=60.0)
    cursor = conn.cursor()
    
    query = """
        SELECT id, name, description, identification_code, toxicity_or_safety, category, dietary_info,
               plain_english_name, purpose, health_risks, risk_level, dietary_safety
        FROM UnifiedIngredients 
        WHERE is_enriched = 0 
           OR description IS NULL OR description = ''
           OR identification_code IS NULL OR identification_code = ''
           OR toxicity_or_safety IS NULL OR toxicity_or_safety = ''
           OR category IS NULL OR category = ''
           OR dietary_info IS NULL OR dietary_info = ''
           OR plain_english_name IS NULL OR plain_english_name = ''
           OR purpose IS NULL OR purpose = ''
           OR health_risks IS NULL OR health_risks = ''
           OR risk_level IS NULL OR risk_level = ''
           OR dietary_safety IS NULL OR dietary_safety = ''
    """
    try:
        cursor.execute(query)
        rows = cursor.fetchall()
    except Exception as e:
        print(f"   [!] Error selecting pending rows: {e}")
        rows = []
        
    conn.close()
    return rows

def save_enriched_results(results):
    if not results: return
    print(f"\nSaving batch of {len(results)} processed ingredients...")
    conn = sqlite3.connect(DB_FILE, timeout=60.0)
    cursor = conn.cursor()
    try:
        for item in results:
            desc, ident, tox, cat, diet_info, plain_english, purpose, risks, risk_level, dietary, row_id = item
            cursor.execute('''
                UPDATE UnifiedIngredients 
                SET description = ?, identification_code = ?, toxicity_or_safety = ?, category = ?, dietary_info = ?,
                    plain_english_name = ?, purpose = ?, health_risks = ?, risk_level = ?, dietary_safety = ?, is_enriched = 1
                WHERE id = ?
            ''', (desc, ident, tox, cat, diet_info, plain_english, purpose, risks, risk_level, dietary, row_id))
        conn.commit()
    except Exception as e:
        print(f"Error saving batch: {e}")
    finally:
        conn.close()

async def ask_ollama_async(session, ingredient_name, current_data, max_retries=3):
    prompt = f"""
Analyze the following food ingredient and output the result in RAW JSON format.
Do not wrap the response in markdown blocks.

Ingredient Name: "{ingredient_name}"

Current Information:
{json.dumps(current_data, indent=2)}

Your task is to provide a complete JSON object updating and filling missing (null or empty) values for ALL the following keys. If a current value is present, you may improve it if it's insufficient, or keep it.

Expected JSON Structure:
{{
  "description": "Short description of what this is",
  "identification_code": "e.g. E-number or INS number if applicable, otherwise 'None'",
  "toxicity_or_safety": "General safety profile",
  "category": "e.g. Preservative, Colorant, Sweetener, etc.",
  "dietary_info": "e.g. Vegan, Halal, Kosher, Gluten-free",
  "plain_english_name": "What average people call this (short)",
  "purpose": "Why manufacturers use it",
  "health_risks": "Health risks or 'Generally recognized as safe'.",
  "risk_level": "Categorize risk exactly as: 'Low', 'Moderate', 'High', or 'Unknown'",
  "dietary_safety": "e.g., vegan, halal, gluten-free"
}}
"""
    payload = {"model": MODEL_NAME, "prompt": prompt, "stream": False, "format": "json"}
    timeout = aiohttp.ClientTimeout(total=90)
    
    for attempt in range(max_retries):
        try:
            async with session.post(OLLAMA_URL, json=payload, timeout=timeout) as response:
                if response.status == 200:
                    res_json = await response.json()
                    return json.loads(res_json.get("response", "{}"))
        except Exception:
            await asyncio.sleep(1)
    return None

async def process_ingredient(semaphore, session, row_data, total, idx, shared_results_list):
    row_id, name, desc, ident, tox, cat, diet_info, plain_english, purpose, risks, risk_level, dietary = row_data
    
    current_data = {
        "description": desc,
        "identification_code": ident,
        "toxicity_or_safety": tox,
        "category": cat,
        "dietary_info": diet_info,
        "plain_english_name": plain_english,
        "purpose": purpose,
        "health_risks": risks,
        "risk_level": risk_level,
        "dietary_safety": dietary
    }
    
    async with semaphore:
        print(f"Processing {idx}/{total}: {name}")
        result_json = await ask_ollama_async(session, name, current_data)
        
        if result_json:
            new_desc = result_json.get("description", desc)
            new_ident = result_json.get("identification_code", ident)
            new_tox = result_json.get("toxicity_or_safety", tox)
            new_cat = result_json.get("category", cat)
            new_diet_info = result_json.get("dietary_info", diet_info)
            new_plain_english = result_json.get("plain_english_name", plain_english)
            new_purpose = result_json.get("purpose", purpose)
            new_risks = result_json.get("health_risks", risks)
            new_risk_lvl = result_json.get("risk_level", risk_level)
            new_dietary = result_json.get("dietary_safety", dietary)
            
            shared_results_list.append((new_desc, new_ident, new_tox, new_cat, new_diet_info, new_plain_english, new_purpose, new_risks, new_risk_lvl, new_dietary, row_id))
            print(f"  -> Processed: {new_plain_english} [Risk: {new_risk_lvl}]")
        else:
            print(f"  -> Skipped due to processing errors.")

async def enrich_data_async():
    rows = fetch_pending_ingredients()
    total_pending = len(rows)
    
    print(f"\nFound {total_pending} unique ingredients to process/fill using Ollama ({MODEL_NAME}).")
    if total_pending == 0: return

    semaphore = asyncio.Semaphore(MAX_CONCURRENT_REQUESTS)
    shared_results = []
    
    print(f"Warming up {MODEL_NAME}...")
    async with aiohttp.ClientSession() as session:
        try:
            await session.post(OLLAMA_URL, json={"model": MODEL_NAME, "prompt": "ping", "stream": False}, timeout=15)
        except Exception: pass

        tasks = []
        for idx, row in enumerate(rows, start=1):
            task = asyncio.create_task(
                process_ingredient(semaphore, session, row, total_pending, idx, shared_results)
            )
            tasks.append(task)
            
            if len(tasks) >= 50 or idx == total_pending:
                await asyncio.gather(*tasks)
                tasks = []
                save_enriched_results(shared_results)
                shared_results.clear()

if __name__ == "__main__":
    print("\n=================================================================")
    print("           OLLAMA LOCAL ASYNC DATABASE ENRICHER")
    print("=================================================================")
    if not test_ollama_connection():
        sys.exit(1)
        
    check_and_merge_journal()

    print("\nStarting Ultra-Fast Ollama Enrichment Phase. Press Ctrl+C to pause.")
    try:
        if sys.platform == 'win32':
            asyncio.set_event_loop_policy(asyncio.WindowsSelectorEventLoopPolicy())
        asyncio.run(enrich_data_async())
    except KeyboardInterrupt:
        print("\nEnrichment paused safely.")
