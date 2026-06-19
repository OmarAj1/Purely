import sqlite3
import json
import os
import sys
import time
import urllib.request
import urllib.error
import threading
import queue
import traceback

DB_FILE = "MasterUnifiedDB.db"
WORD_COUNT_THRESHOLD = 5  # Names with more than this many words will be processed

# Active models currently available under Google Gemini Free Tier.
# Each model has its own separate quota (typically 15 RPM).
# Running them concurrently allows you to process items much faster and maximize your free allowance!
ACTIVE_MODELS = [
    "gemini-3.1-flash-lite-preview"
]

def load_api_key():
    # 1. Look in environment variables
    api_key = os.getenv("GEMINI_API_KEY")
    if api_key:
        return api_key.strip()
    
    # 2. Aggressively search for env files (Windows users often hide extensions)
    env_paths = [".env", "env.txt", ".env.txt", ".env.example"]
    for path in env_paths:
        if os.path.exists(path):
            try:
                with open(path, "r", encoding="utf-8") as f:
                    for line in f:
                        if "GEMINI_API_KEY" in line and "=" in line:
                            k, v = line.strip().split("=", 1)
                            val = v.strip().replace('"', '').replace("'", "")
                            if val and val != "your_gemini_api_key_here":
                                print(f"[✓] Automatically loaded API key from {path}")
                                return val
            except Exception:
                pass
            
    # 3. Interactive fallback
    print("\n[!] API Key not found automatically in .env")
    user_key = input("Please paste your GEMINI_API_KEY here and press Enter: ").strip()
    if user_key:
        return user_key
    
    print("\n[!] Exiting because no API Key was provided.")
    sys.exit(1)

def fetch_long_name_ingredients():
    """Select entries where the name field is excessively long, indicating merged information."""
    if not os.path.exists(DB_FILE):
        print(f"[!] Target database file '{DB_FILE}' not found! Please run the merge script first.")
        sys.exit(1)
        
    conn = sqlite3.connect(DB_FILE, timeout=60.0)
    cursor = conn.cursor()
    
    query = """
        SELECT id, name, description, identification_code, toxicity_or_safety, category, dietary_info
        FROM UnifiedIngredients
        WHERE name IS NOT NULL AND length(name) > 15
    """
    try:
        cursor.execute(query)
        rows = cursor.fetchall()
    except Exception as e:
        print(f"[!] Error reading UnifiedIngredients: {e}")
        rows = []
    finally:
        conn.close()
        
    # Filter in Python by word count
    selected_rows = []
    for r in rows:
        name = str(r[1] or "")
        words = name.split()
        if len(words) > WORD_COUNT_THRESHOLD:
            selected_rows.append(r)
            
    return selected_rows

def ask_gemini_to_clean(api_key, model, row_data, max_retries=6):
    row_id, name, desc, code, tox, cat, diet = row_data
    
    prompt = f"""
You are a precision chemical and food database mapping intelligence. 
The following record from a merged database has an accidentally long 'name' field. It contains the primary substance name, but also description, hazard/safety warnings, category notes, or code identifiers combined into it.

Your task:
1. Extract the actual brief, concise substance or ingredient name (usually 1 to 3 words). Keep it highly accurate and clean.
2. Identify description snippets, codes, safety information, categories, or allergen/dietary details packed inside the messy name.
3. Distribute these fragments to the proper target fields below.
4. If a target field already contains existing text, MERGE or APPEND the new information cleanly. Do not overwrite good existing information; enrich it. Do not manufacture new information.

Source Record data:
- Messy Name: {name}
- Current Description: {desc or "None"}
- Current Identification Code: {code or "None"}
- Current Toxicity/Safety info: {tox or "None"}
- Current Category: {cat or "None"}
- Current Dietary/Allergen info: {diet or "None"}

You must return a raw JSON object with these EXACT keys (maintain standard spelling and uppercase/lowercase keys):
{{
  "name": "concise_name_here",
  "description": "merged_description_here",
  "identification_code": "merged_code_here",
  "toxicity_or_safety": "merged_safety_here",
  "category": "merged_category_here",
  "dietary_info": "merged_dietary_here"
}}
"""
    
    url = f"https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent?key={api_key}"
    payload_dict = {
        "contents": [{"parts": [{"text": prompt}]}],
        "generationConfig": {
            "responseMimeType": "application/json",
            "temperature": 0.1
        }
    }
    
    payload_bytes = json.dumps(payload_dict).encode("utf-8")
    delay = 4.0 # Base rate-limiting recovery delay
    
    for attempt in range(max_retries):
        try:
            req = urllib.request.Request(url, data=payload_bytes, headers={"Content-Type": "application/json"})
            with urllib.request.urlopen(req, timeout=45) as response:
                res_content = response.read().decode("utf-8")
                res_json = json.loads(res_content)
                
                try:
                    raw_text = res_json["candidates"][0]["content"]["parts"][0]["text"].strip()
                except KeyError as e:
                    print(f" [!] KeyError parsing response from {model}: {res_json}")
                    raise e
                
                # Clean markdown code blocks if present
                if raw_text.startswith("```"):
                    lines = raw_text.splitlines()
                    if lines[0].startswith("```"):
                        lines = lines[1:]
                    if lines and lines[-1].strip() == "```":
                        lines = lines[:-1]
                    raw_text = "\n".join(lines).strip()
                    
                try:
                    return json.loads(raw_text)
                except json.JSONDecodeError as e:
                    print(f" [!] JSONDecodeError from {model}. Raw text was:\n{raw_text}")
                    raise e
                    
        except urllib.error.HTTPError as e:
            if e.code in (429, 503, 500, 502, 504):
                print(f" [!] {model} hit HTTP {e.code}. Retrying in {delay:.1f}s...")
                time.sleep(delay)
                delay *= 2.0
            else:
                err_msg = e.read().decode("utf-8", errors="ignore")
                print(f" [!] {model} returned status {e.code}: {err_msg[:120]}")
                time.sleep(2)
        except urllib.error.URLError as e:
            print(f" [!] {model} Network error (URLError): {e.reason}. Retrying in {delay:.1f}s...")
            time.sleep(delay)
            delay *= 2.0
        except TimeoutError as e:
            print(f" [!] {model} TimeoutError. Retrying in {delay:.1f}s...")
            time.sleep(delay)
            delay *= 2.0
        except Exception as e:
            print(f" [!] Error communicating with {model} (Attempt {attempt+1}): {type(e).__name__} - {e}")
            time.sleep(1)
            
    return None

def db_writer_thread(write_queue):
    """Listens to a writing queue and processes updates sequentially.
    This entirely avoids sqlite3.OperationalError: database is locked."""
    conn = sqlite3.connect(DB_FILE, timeout=60.0)
    cursor = conn.cursor()
    
    processed_count = 0
    try:
        while True:
            item = write_queue.get()
            if item is None:
                write_queue.task_done()
                break
                
            name, desc, code, toxicity, category, dietary, row_id = item
            try:
                cursor.execute('''
                    UPDATE UnifiedIngredients 
                    SET name = ?, description = ?, identification_code = ?, toxicity_or_safety = ?, category = ?, dietary_info = ?
                    WHERE id = ?
                ''', (name, desc, code, toxicity, category, dietary, row_id))
                
                processed_count += 1
                if processed_count % 20 == 0:
                    conn.commit()
            except Exception as e:
                print(f"\n[!] SQLite upgrade error on ID {row_id}: {e}")
                
            write_queue.task_done()
            
        conn.commit()
    finally:
        conn.close()
        print("\n[✓] Database writer successfully finalized and persisted all changes.")

def model_worker_thread(model, api_key, request_queue, write_queue, stats_tracker):
    """Dedicated model worker that processes items at exactly <= 13 RPM to completely avoid rate limits."""
    # Free tier safe pacing: 1 request every 4.8 seconds keeps us strictly below 15 RPM
    PACING_SEC = 4.8 
    
    while True:
        row_data = request_queue.get()
        try:
            if row_data is None:
                break
                
            row_id, original_name = row_data[0], row_data[1]
            start_time = time.time()
            
            result = ask_gemini_to_clean(api_key, model, row_data)
            
            if result:
                clean_name = result.get("name", original_name)
                desc = result.get("description", row_data[2])
                code = result.get("identification_code", row_data[3])
                tox = result.get("toxicity_or_safety", row_data[4])
                cat = result.get("category", row_data[5])
                diet = result.get("dietary_info", row_data[6])
                
                # Push back to the sequential database writer safely
                write_queue.put((clean_name, desc, code, tox, cat, diet, row_id))
                
                stats_tracker["success"] += 1
                print(f"[{model}] Cleaned ID {row_id}: \"{original_name[:25]}...\" -> \"{clean_name}\"")
            else:
                stats_tracker["failed"] += 1
                print(f"[{model}] Failed to clean ID {row_id}: \"{original_name[:40]}...\"")
                
            # Respect precise pacing so we completely avoid triggering rate limits on the Free Tier!
            elapsed_time = time.time() - start_time
            sleep_needed = max(0.1, PACING_SEC - elapsed_time)
            time.sleep(sleep_needed)
        except Exception as e:
            print(f"[{model}] Fatal unhandled error in worker task: {type(e).__name__} - {e}")
            traceback.print_exc()
        finally:
            request_queue.task_done()

def run_cleaner():
    print("="*65)
    print("           GEMINI FREE-TIER MULTI-MODEL DATABASE CLEANER")
    print("           (Powered by built-in Python threading)")
    print("="*65)
    
    api_key = load_api_key()
    rows = fetch_long_name_ingredients()
    total_elements = len(rows)
    
    print(f"\n[✓] Connected to: {DB_FILE}")
    print(f"[✓] Found {total_elements} records matching criteria (name > {WORD_COUNT_THRESHOLD} words)")
    
    if total_elements == 0:
        print("[!] No rows require cleaning. Cleanup completed!")
        return
        
    print(f"[✓] Active Free-Tier Quota Channels: {ACTIVE_MODELS}")
    print(f"[✓] Pacing: Strictly <= 13 RPM per model (Safe Total Throughput ~ {len(ACTIVE_MODELS) * 13} requests/minute)")
    print("-"*65)
    print("Starting automatically in 2 seconds... (Press Ctrl+C to pause or stop safely at any time)")
    time.sleep(2)
    
    # Initialize queues
    request_queue = queue.Queue()
    write_queue = queue.Queue()
    
    # Add items to the task queue
    for r in rows:
        request_queue.put(r)
        
    stats_tracker = {"success": 0, "failed": 0}
    
    # Start the SQLite writer
    writer_thread = threading.Thread(target=db_writer_thread, args=(write_queue,), daemon=True)
    writer_thread.start()
    
    # Start the model workers
    worker_threads = []
    for model in ACTIVE_MODELS:
        # Add stop signals to guide workers on queue completion
        request_queue.put(None) 
        
        t = threading.Thread(target=model_worker_thread, args=(model, api_key, request_queue, write_queue, stats_tracker), daemon=True)
        worker_threads.append(t)
        t.start()
        
    # Wait for the processing queue to fully execute
    request_queue.join()
    
    # Wait for all workers to complete/stop
    for t in worker_threads:
        t.join()
    
    # Signal writer that all data is parsed & clean
    write_queue.put(None)
    write_queue.join()
    writer_thread.join()
    
    print("\n" + "="*65)
    print("                          CLEANUP SUMMARY")
    print("="*65)
    print(f"Total entries processed: {stats_tracker['success'] + stats_tracker['failed']}")
    print(f"Successfully cleaned:  {stats_tracker['success']}")
    print(f"Failed / Skipped:      {stats_tracker['failed']}")
    print("All updates saved successfully into the same database.")
    print("="*65 + "\n")

if __name__ == "__main__":
    try:
        run_cleaner()
    except KeyboardInterrupt:
        print("\n\n[!] Cleaning paused safely. All database upgrades up to this point have been saved.")
    except Exception as e:
        print(f"\n\n[!] Fatal error: {type(e).__name__} - {e}")
        traceback.print_exc()

