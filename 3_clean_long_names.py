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

# Active models for Ollama.
# We will use several threads targeting the same model name.
ACTIVE_MODELS = [
    "llama3.2",
    "llama3.2",
    "llama3.2",
    "llama3.2",
    "llama3.2"
]

def load_api_key():
    return "ollama_local"

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
            for model in set(ACTIVE_MODELS):
                model_base = model.split(':')[0]
                installed = any(m.startswith(model_base) for m in models)
                if not installed:
                    print(f"\n[!] WARNING: Model '{model}' may not be installed.")
                    print(f"    Please run 'ollama run {model}' in your terminal to download it.")
            
            return True
    except urllib.error.URLError as e:
        print("\n[!] FATAL ERROR: Cannot connect to Ollama.")
        print(f"    Details: {e.reason}")
        print("\n    Please make sure Ollama is installed and running!")
        print("    You can start it by opening a new terminal and typing:")
        print("        ollama serve")
        return False
    except Exception as e:
        print(f"\n[!] Unexpected error checking Ollama: {e}")
        return False

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

# Pacing for Ollama models (can be much faster depending on local GPU!)
PACING_SEC = 0.5

def ask_ollama_to_clean(api_key, model, row_data, max_retries=10):
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
    
    url = "http://localhost:11434/api/generate"
    payload_dict = {
        "model": model,
        "prompt": prompt,
        "stream": False,
        "format": "json"
    }
    
    payload_bytes = json.dumps(payload_dict).encode("utf-8")
    delay = 10.0 # Base recovery delay
    
    for attempt in range(max_retries):
        try:
            req = urllib.request.Request(url, data=payload_bytes, headers={"Content-Type": "application/json"})
            with urllib.request.urlopen(req, timeout=90) as response: # Ollama can be slow
                res_content = response.read().decode("utf-8")
                res_json = json.loads(res_content)
                
                try:
                    raw_text = res_json["response"].strip()
                except KeyError as e:
                    print(f" [!] KeyError parsing response from {model}: {res_json}")
                    raise e
                
                try:
                    return json.loads(raw_text)
                except json.JSONDecodeError as e:
                    print(f" [!] JSONDecodeError from {model}. Raw text was:\n{raw_text}")
                    raise e
                    
        except urllib.error.HTTPError as e:
            err_msg = e.read().decode("utf-8", errors="ignore")
            print(f" [!] {model} hit HTTP {e.code}: {err_msg[:120]}... Retrying in {delay:.1f}s...")
            time.sleep(delay)
            delay *= 2.0
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
    """Dedicated model worker that processes items slowly to avoid rate limits."""
    
    while True:
        row_data = request_queue.get()
        try:
            if row_data is None:
                break
                
            row_id, original_name = row_data[0], row_data[1]
            start_time = time.time()
            
            result = ask_ollama_to_clean(api_key, model, row_data)
            
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
                
            elapsed_time = time.time() - start_time
            sleep_needed = max(0.1, PACING_SEC - elapsed_time)
            time.sleep(sleep_needed)
        except urllib.error.HTTPError as e:
            print(f"[{model}] Worker thread is shutting down due to fatal error: HTTP {e.code}")
            request_queue.put(row_data) # Requeue the item for a different model
            break
        except Exception as e:
            print(f"[{model}] Fatal unhandled error in worker task: {type(e).__name__} - {e}")
            traceback.print_exc()
        finally:
            request_queue.task_done()

def run_cleaner():
    print("="*65)
    print("           OLLAMA LOCAL MULTI-THREAD DATABASE CLEANER")
    print("           (Powered by built-in Python threading)")
    print("="*65)
    
    if not test_ollama_connection():
        sys.exit(1)
        
    check_and_merge_journal()
    
    api_key = load_api_key()
    rows = fetch_long_name_ingredients()
    total_elements = len(rows)
    
    print(f"\n[✓] Connected to: {DB_FILE}")
    print(f"[✓] Found {total_elements} records matching criteria (name > {WORD_COUNT_THRESHOLD} words)")
    
    if total_elements == 0:
        print("[!] No rows require cleaning. Cleanup completed!")
        return
        
    print(f"[✓] Active Ollama Models: {ACTIVE_MODELS}")
    print(f"[✓] Pacing: Minimum {PACING_SEC}s between requests")
    print("-"*65)
    print("Starting... (Press Ctrl+C to pause or stop safely at any time)")
    
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
        t = threading.Thread(target=model_worker_thread, args=(model, api_key, request_queue, write_queue, stats_tracker), daemon=True)
        worker_threads.append(t)
        t.start()
        
    try:
        # Wait for the processing queue to fully execute. Use a loop so KeyboardInterrupt can be caught.
        last_print_time = time.time()
        while not request_queue.empty():
            time.sleep(1.0)
            if time.time() - last_print_time >= 10.0:
                print(f"[⌛] Progress: {stats_tracker['success'] + stats_tracker['failed']} / {total_elements} processed. {request_queue.qsize()} remaining in queue.", flush=True)
                last_print_time = time.time()
                
            if not any(t.is_alive() for t in worker_threads):
                print("\n[!] All model workers have died permanently! Aborting processing...")
                break
            
        # Add stop signals to guide workers on queue completion
        for _ in ACTIVE_MODELS:
            request_queue.put(None)
            
        # If we aborted because all workers died, we shouldn't join on the queue, because nobody will consume the remaining items.
        if any(t.is_alive() for t in worker_threads):
            request_queue.join()
        else:
            # Fake drain the queue so we can exit gracefully
            with request_queue.mutex:
                request_queue.queue.clear()
                request_queue.unfinished_tasks = 0
                request_queue.all_tasks_done.notify_all()
        
        # Wait for all workers to complete/stop
        for t in worker_threads:
            t.join()
            
    except KeyboardInterrupt:
        print("\n\n[!] Process interrupted (Ctrl+C). Saving current progress and shutting down safely...")
        # Clear the remaining tasks so we can exit gracefully
        with request_queue.mutex:
            request_queue.queue.clear()
            request_queue.unfinished_tasks = 0
            request_queue.all_tasks_done.notify_all()
        # We don't join the remaining worker threads because they are daemons and will naturally die or are sleeping
        
    finally:
        # Signal writer that all data is parsed & and it should finalize transactions
        print("\n[!] Flushing final records to database and cleaning up journal...")
        write_queue.put(None)
        write_queue.join()
        writer_thread.join()
        
        print("\n" + "="*65)
        print("                          CLEANUP SUMMARY")
        print("="*65)
        print(f"Total entries processed: {stats_tracker['success'] + stats_tracker['failed']}")
        print(f"Successfully cleaned:  {stats_tracker['success']}")
        print(f"Failed / Skipped:      {stats_tracker['failed']}")
        print("All updates saved successfully into the database. Safe to exit.")
        print("="*65 + "\n")

if __name__ == "__main__":
    run_cleaner()

