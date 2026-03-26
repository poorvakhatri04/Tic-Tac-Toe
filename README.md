# Synthetic Dataset Generation for RAG
 
A SaaS platform and Python SDK that turns raw documents into high-accuracy synthetic QA datasets for benchmarking Retrieval-Augmented Generation (RAG) systems — powered by **LangGraph**, **litellm**, **FastAPI**, and **Opik** tracing.
 
> **Current branch:** `updated_architecture` — Two-phase pipeline with Python threading for parallel QA and Multi-Hop generation. Persist nodes embedded directly inside each subgraph.
 
---
 
## Table of Contents
 
1. [Project Structure](#project-structure)
2. [Quick Start](#quick-start)
3. [API Endpoints](#api-endpoints)
4. [SDK Usage](#sdk-usage)
5. [Database Schema](#database-schema)
6. [Pipeline Architecture](#pipeline-architecture)
7. [Phase 1 — Batch Context Generation](#phase-1--batch-context-generation)
8. [Phase 2 — QA Generation (per-batch)](#phase-2--qa-generation-per-batch)
9. [Phase 2 — Multi-Hop Generation (project-wide)](#phase-2--multi-hop-generation-project-wide)
10. [GraphState — Full Field Reference](#graphstate--full-field-reference)
11. [Evaluation Strategy](#evaluation-strategy)
12. [LLM Configuration](#llm-configuration)
 
---
 
## Project Structure
 
```
.
├── backend/
│   ├── database/
│   │   ├── models.py               # SQLAlchemy ORM models
│   │   └── session.py              # Engine + SessionLocal + get_db()
│   └── src/
│       ├── prompts.py              # All LLM prompt templates
│       ├── api/
│       │   ├── main.py             # FastAPI app entry-point
│       │   ├── schemas.py          # Pydantic request/response models
│       │   └── routes/
│       │       ├── projects.py     # POST /projects/  GET /projects/{id}
│       │       ├── ingest.py       # POST /ingest/  (triggers full pipeline async)
│       │       └── dataset.py      # GET /dataset/  GET /dataset/status
│       └── graph/
│           ├── llm.py              # litellm wrapper + parse_json_array
│           ├── state.py            # GraphState TypedDict + reducers + initial_state()
│           ├── workflow.py         # run_full_pipeline() — 2-phase, threaded orchestrator
│           └── subgraphs/
│               ├── batch_context/       # Phase 1 – LLM context summary per batch
│               │   ├── graph.py         # Compiled subgraph
│               │   └── nodes.py         # create_batch_context_node
│               ├── qa_generation/       # Phase 2 – QA gen + L1 + L2 eval + persist
│               │   ├── graph.py         # Compiled subgraph
│               │   └── nodes.py         # generate, level1_evaluate, level2_evaluate
│               ├── multihop_generation/ # Phase 2 – cross-doc multi-hop + persist
│               │   ├── graph.py         # Compiled subgraph
│               │   └── nodes.py         # build_context, generate, evaluate, finish_round
│               └── persist/             # Shared persist nodes (embedded in subgraphs)
│                   └── nodes.py         # persist_qa_node, persist_multihop_node
├── sdk/
│   └── synthetic_dataset_sdk/      # SDK core files
├── frontend/
│   └── src/                        # React + Vite UI
├── tests/
│   ├── test_db_connection.py
│   └── test_pipeline.py            # End-to-end SDK test (uses existing project)
├── alembic/                        # Database migrations
├── requirements.txt
└── Dummy-Data/                     # Sample JSON documents for testing
```
 
---
 
## Quick Start
 
### 1. Configure environment
 
```bash
# Create backend/.env with:
DATABASE_URL=postgresql://user:password@host:5432/dbname
LLM_API_KEY=sk-or-v1-...          # OpenRouter API key
LLM_MODEL=openrouter/meta-llama/llama-3.3-70b-instruct
```
 
### 2. Install dependencies
 
```bash
pip install -r requirements.txt
```
 
### 3. Run database migrations
 
```bash
alembic upgrade head
```
 
### 4. Start the Frontend
```bash
cd frontend
npm install
npm run dev
```
 
### 5. Start the API server
```powershell
uvicorn backend.src.api.main:app --reload --port 8000
# Swagger UI at http://localhost:8000/docs
```
 
### 5. Run the test script
 
```powershell
# Edit tests/test_pipeline.py and set your API_KEY, then:
python tests/test_pipeline.py
```
 
---
 
## API Endpoints
 
| Method | Path | Auth | Description |
|--------|------|------|-------------|
| `POST` | `/projects/` | — | Create project, receive `SDGKey` |
| `GET` | `/projects/{id}` | — | Get project metadata |
| `POST` | `/ingest/` | `SDGKey` | Upload pages, trigger full pipeline (async) |
| `GET` | `/dataset/` | `SDGKey` | Fetch generated QA dataset |
| `GET` | `/dataset/status` | `SDGKey` | Check batch processing progress |
| `GET` | `/health` | — | Health check |
 
All authenticated endpoints read the SDGKey from the `SDGKey` HTTP header.
 
---
 
## SDK Usage
 
```python
import json
from sdk import SyntheticDatasetClient
 
# 1. Create a project via POST /projects/ (one-time)
import requests
proj = requests.post("http://localhost:8000/projects/", json={"name": "my-project"}).json()
SDG_KEY = proj["SDGKey"]
 
# 2. Initialise the client
# No need to specify base_url if using the live API at https://sdg-api.shashanksahu.live
# For local development: sdk = SyntheticDatasetClient(sdg_key=SDG_KEY, base_url="http://localhost:8000")
sdk = SyntheticDatasetClient(sdg_key=SDG_KEY)  
 
# 3. Load your document pages and upload
pages = json.load(open("Dummy-Data/docData.json"))
result = sdk.upload(pages=pages)
# → {"project_id": "...", "total_batches": 6, "job_status": "processing"}
 
# 4. Poll pipeline progress
status = sdk.get_batch_status(doc_id="DOC-A")
 
# 5. Fetch results
dataset = sdk.get_dataset()               # good pairs only
dataset = sdk.get_dataset(include_faulty=True)  # include rejected pairs
```
 
**Input page format:**
```json
[
  { "page_no": 1, "doc_id": "DOC-A", "text": "..." },
  { "page_no": 2, "doc_id": "DOC-A", "text": "..." }
]
```
 
---
 
## Database Schema
 
### `projects`
| Column | Type | Notes |
|--------|------|-------|
| `id` | UUID PK | Auto-generated |
| `name` | VARCHAR(255) | Project name |
| `sdg_key` | VARCHAR(255) | Unique, used for SDK auth (`SDGKey` header/body) |
| `created_at` | TIMESTAMP | |
 
### `batches`
| Column | Type | Notes |
|--------|------|-------|
| `id` | UUID PK | |
| `project_id` | UUID FK → projects | CASCADE delete |
| `doc_id` | VARCHAR(255) | Human label from input JSON, e.g. `"DOC-A"` |
| `page_no` | INTEGER | Source page number |
| `batch_index` | INTEGER | 0-based batch counter |
| `text` | TEXT | Raw 2-paragraph batch content |
| `batch_context` | TEXT | LLM-generated dense knowledge summary |
| `status` | VARCHAR(50) | `"processing"` → `"done"` |
| `created_at` / `updated_at` | TIMESTAMP | |
 
### `dataset_entries`
| Column | Type | Notes |
|--------|------|-------|
| `id` | UUID PK | |
| `project_id` | UUID FK → projects | CASCADE delete |
| `batch_ids` | UUID[] | Array of Batch UUIDs whose contexts fed this pair |
| `question` | TEXT | Generated question |
| `answer` | TEXT | Generated answer |
| `source_page_numbers` | INTEGER[] | Pages referenced |
| `evaluation_scores` | JSONB | Metric scores, e.g. `{"level2_avg": 0.82, "multihop_eval": 1.0}` |
| `overall_accuracy` | FLOAT | Composite 0–1 score |
| `is_faulty` | BOOLEAN | `true` if pair was rejected |
| `created_at` | TIMESTAMP | |
 
---
 
## Pipeline Architecture
 
The pipeline is invoked as a **background thread** by `POST /ingest/` so the HTTP response returns immediately. It is fully **resumable**, supports **heartbeats**, and integrates with **Opik** for tracing.
 
```
POST /ingest/
     │
     └── background thread → run_full_pipeline(project_id, pages)
              │
              ├── Step 0: Split pages into 2-paragraph batches
              │           Bulk-insert all Batch rows into DB (status="pending")
              │
              ├── Phase 1: Context Generation  (sequential, 1 subgraph per batch)
              │    for batch_index in range(total_batches):
              │        build_batch_context_subgraph().invoke(...)
              │        → LLM summary stored in DB (status="done")
              │
              └── Phase 2: QA + Multi-Hop  (two independent Python threads)
                   │
                   ├── Thread A — QA Generation (per-batch, sequential)
                   │    for each batch (in order):
                   │        build_qa_generation_subgraph().invoke(...)
                   │        → generate → L1 eval → L2 eval → persist_qa → DB
                   │
                   └── Thread B — Multi-Hop Generation (project-wide, once)
                            build_multihop_generation_subgraph().invoke(...)
                            → rounds of DB-sampled context → generate → evaluate
                              → persist_multihop → DB
                   (both threads run concurrently; pipeline waits for both to finish)
```
 
**Key design decisions:**
- **No LangGraph parent graph** — avoids fan-out reducer conflicts when subgraphs return full state snapshots.
- **Persist nodes are embedded inside each subgraph** (`persist_qa` at end of QA subgraph; `persist_multihop` at end of multihop subgraph) — data is committed immediately when each subgraph finishes, not held in memory.
- **Phase 1 must complete before Phase 2 starts** — multihop requires all batch contexts to exist in DB before it can sample from them.
- **QA recursion_limit=50** per batch (generate×5 + L1 + L2 + persist ≤ 8 supersteps at most).
- **Multihop recursion_limit=500** for the whole project (rounds × nodes/round ≤ ~140 supersteps).
 
---
 
## Phase 1 — Batch Context Generation
 
**Subgraph topology:**
 
```
START
  ↓
create_batch_context_node   ← reads Batch row from DB, calls LLM for summary
  ↓
END
```
 
**What happens:**
1. `workflow.py` reads the batch from DB by `(project_id, batch_index)`
2. Calls LLM with the raw 2-paragraph text → generates a dense knowledge summary
3. Updates `Batch.batch_context` in DB and sets `status = "done"`
4. Repeats for every batch sequentially — all contexts are stored before Phase 2 starts
 
This guarantees the multihop thread can sample from the full corpus as soon as Phase 2 begins.
 
**State written:**
 
| Key | Description |
|-----|-------------|
| `batch_context` | LLM-generated dense knowledge summary of the 2-paragraph batch |
| `current_batch_id` | UUID of the current `Batch` DB row |
| `current_batch_page_no` | Source page number |
 
---
 
## Phase 2 — QA Generation (per-batch)
 
**Subgraph topology:**
 
```
START
  ↓
generate_qa_batch  ←──────────────────────────────────────────┐
  ↓ (accumulated ≥ MIN_QA_PAIRS OR hits ≥ MAX_GEN_HITS)        │
  ├── "generate_more" ──────────────────────────────────────────┘
  └── "level1_eval"
       ↓
  level1_evaluate ──── "regenerate" (up to MAX_L1_REGEN times) ──→ generate_qa_batch (clear + restart)
       ↓ "level2_eval"
  level2_evaluate
       ↓
  persist_qa   ← commit accepted pairs to DB immediately
       ↓
  END
```
 
**`generate_qa_batch_node`**
- Calls LLM once → produces a JSON array of Q&A pairs (at least `PAIRS_PER_HIT=10`)
- LLM is free to generate more if context supports it — up to `remaining_slots` (= `MAX_QA_PAIRS - accumulated`)
- Accumulates into `generated_dataset` across calls
- Loops back if `total < MIN_QA_PAIRS` and `hits < MAX_GEN_HITS`
- Temperature increases slightly each hit (`+0.05`) for diversity
 
**`level1_evaluate_node`** — Holistic batch judge
- Sends the entire `generated_dataset` to LLM for a single `PASS`/`FAIL` verdict
- Assesses groundedness, relevance, accuracy, diversity, and overall quality as a whole
- **PASS** → proceed to Level 2
- **FAIL** → wipe `generated_dataset` completely and restart generation (up to `MAX_L1_REGEN=3` times)
 
**`level2_evaluate_node`** — Per-pair metric scoring
- Sends all pairs to LLM in one call (CSV-formatted); LLM scores each on 5 metrics:
 
| Metric | What it measures |
|--------|-----------------|
| `faithfulness` | Answer claims are fully supported by the context |
| `answer_relevancy` | Answer directly addresses the question asked |
| `context_relevancy` | Question is relevant to what the context covers |
| `context_precision` | Context was genuinely useful to arrive at the answer |
| `context_utilization` | Answer makes good use of what the context provides |
 
- Any pair with average score `< LEVEL2_THRESHOLD (0.6)` is removed
- Survivors are written to `generated_dataset` (cleaned list passed to persist)
 
**`persist_qa_node`**
- Iterates the accepted pairs, bulk-inserts `DatasetEntry` rows into DB
- Clears `generated_dataset` to `[]` (frees memory before next batch starts)
 
**Thresholds:**
 
| Constant | Value | Meaning |
|----------|-------|---------|
| `MIN_QA_PAIRS` | 10 | Minimum pairs before moving to L1 eval |
| `MAX_QA_PAIRS` | 50 | Hard cap on accumulated pairs per batch |
| `PAIRS_PER_HIT` | 10 | Minimum pairs requested per LLM generation call |
| `MAX_GEN_HITS` | 5 | Max LLM generation calls per batch |
| `MAX_L1_REGEN` | 3 | Max Level 1 holistic regen cycles |
| `LEVEL2_THRESHOLD` | 0.6 | Per-pair avg score below this → removed |
 
**Typical output:** 10–50 QA pairs per batch, all scored and grounded in that batch's context.
 
---
 
## Phase 2 — Multi-Hop Generation (project-wide)
 
**Subgraph topology:**
 
```
START
  ↓
build_multihop_context  ←──────────────────────────────── "next_round" ──────────┐
  ↓ "skip" (no DB contexts yet)                                                   │
  ↓ "generate"                                                                    │
generate_multihop_batch  ←──────────── "regenerate" (up to MAX_MULTIHOP_REGEN) ──┐│
  ↓ (≥ MIN_MULTIHOP_PAIRS OR hits ≥ MAX_MULTIHOP_GEN_HITS)                       ││
  ├── "generate_more" ────────────────────────────────────────────────────────────┘│
  └── "evaluate"                                                                   │
       ↓                                                                           │
  evaluate_multihop ──── "regenerate" ─────────────────────────────────────────────┘
       ↓ "accept"
  finish_round ──── "next_round" ─────────────────────────────────────────────────┐
       ↓ "done"                                                                    │
  persist_multihop  ← commit all accumulated rounds to DB                         │
       ↓                                                                           │
  END                         (loops back to build_multihop_context for each round)┘
```
 
**`build_multihop_context_node`** (entry point of each round)
- Queries PostgreSQL for all `Batch` rows where `batch_context IS NOT NULL`
- **Randomly samples** up to `MULTIHOP_RANDOM_BATCHES=10` rows — fresh independent pick per round
- Assembles a labelled multi-context block:
  ```
  === BATCH CONTEXT 1 ===
  <summary>
  === BATCH CONTEXT 2 ===
  <summary>
  ... up to 10
  ```
- Resets per-round state: `multihop_round_dataset = []`, `multihop_attempts = 0`, `multihop_generation_hits = 0`
- If DB returns 0 rows → routes to `"skip"` → END immediately (safe on empty corpus)
 
**`generate_multihop_batch_node`**
- Calls LLM once → produces ~`MULTIHOP_PAIRS_PER_HIT=12` multi-hop Q&A pairs requiring cross-context reasoning
- Accumulates into `multihop_round_dataset`
- Loops until `multihop_round_dataset ≥ MIN_MULTIHOP_PAIRS` OR `multihop_generation_hits ≥ MAX_MULTIHOP_GEN_HITS`
 
**`evaluate_multihop_node`**
- Sends the entire `multihop_round_dataset` to LLM for a holistic `PASS`/`FAIL` verdict
- **PASS** → mark all pairs `is_faulty=False`, route to `finish_round`
- **FAIL** → clear `multihop_round_dataset` only (pairs from previous rounds are untouched); retry up to `MAX_MULTIHOP_REGEN=3` times
 
**`finish_round_node`**
- Appends accepted `multihop_round_dataset` into cumulative `multihop_dataset`
- Increments `multihop_round` counter
- Clears `multihop_round_dataset`
- Routes: `multihop_round < total_rounds` → `"next_round"` → back to `build_multihop_context`; else → `"done"` → `persist_multihop`
 
**`persist_multihop_node`**
- Bulk-inserts all accumulated `multihop_dataset` pairs as `DatasetEntry` rows
- Clears `multihop_dataset` to `[]`
 
**Round count (`compute_multihop_rounds`):**
 
| `total_batches` | Rounds |
|---|---|
| ≤ 60 | 3 |
| > 60 | `total_batches // 20` |
 
**Thresholds:**
 
| Constant | Value | Meaning |
|----------|-------|---------|
| `MIN_MULTIHOP_PAIRS` | 20 | Minimum pairs per round before evaluating |
| `MAX_MULTIHOP_PAIRS` | 80 | Hard cap per round |
| `MULTIHOP_PAIRS_PER_HIT` | 12 | Target pairs per LLM generation call |
| `MAX_MULTIHOP_GEN_HITS` | 8 | Max LLM generation calls per round |
| `MAX_MULTIHOP_REGEN` | 3 | Max holistic regen cycles per round |
| `MULTIHOP_RANDOM_BATCHES` | 10 | DB rows randomly sampled per round |
 
---
 
## GraphState — Full Field Reference
 
### Identity & Run Config
 
| Field | Type | Description |
|-------|------|-------------|
| `project_id` | `str` | UUID of the owning Project row |
| `total_batches` | `int` | Total batches pre-inserted for this run |
| `batch_index` | `int` | Current position (0-based) |
 
### Batch Context
 
| Field | Type | Description |
|-------|------|-------------|
| `current_batch_text` | `str` | Raw 2-paragraph text of the current batch |
| `current_batch_page_no` | `int` | Source page number |
| `current_batch_doc_id` | `str` | Source doc_id (e.g. `"DOC-A"`) |
| `current_batch_id` | `str` | UUID of the current `Batch` DB row |
| `batch_context` | `str` | LLM knowledge summary of current batch |
| `batch_context_history` | `list[str]` | All past summaries (oldest → newest) |
 
### QA Generation
 
| Field | Type | Reducer | Description |
|-------|------|---------|-------------|
| `generated_dataset` | `list[QAPair]` | `_keep_populated` | Pairs being accumulated; cleared on L1 regen and after persist |
| `previous_questions` | `list[str]` | `_last` | Diversity guard — questions already generated this batch |
| `qa_generation_hits` | `int` | `_last` | Number of LLM generation calls this batch |
| `regeneration_attempts` | `int` | `_last` | Level 1 regen counter (max `MAX_L1_REGEN`) |
| `validated_dataset` | `list[QAPair]` | `_last` | Filtered pairs after Level 2 evaluation |
 
### Multi-Hop Generation
 
| Field | Type | Reducer | Description |
|-------|------|---------|-------------|
| `multihop_context` | `str` | `_last` | Concatenated summaries from randomly sampled batches |
| `multihop_batch_ids` | `list[str]` | `_last` | DB UUIDs of the sampled batches |
| `multihop_dataset` | `list[QAPair]` | `_keep_populated` | Accumulated multi-hop pairs across all rounds |
| `multihop_round_dataset` | `list[QAPair]` | `_last` | Pairs generated in the current round only |
| `multihop_round` | `int` | `_last` | Current round index (0-based) |
| `multihop_generation_hits` | `int` | `_last` | LLM generation calls in current round |
| `multihop_attempts` | `int` | `_last` | Holistic regen attempts in current round |
 
### Pipeline Control
 
| Field | Type | Reducer | Description |
|-------|------|---------|-------------|
| `is_done` | `bool` | `_last` | Terminal flag set by routing helpers to stop loops |
 
---
 
## Evaluation Strategy
 
Each batch of QA pairs passes through a **two-level LLM-only evaluation** before being persisted.
 
### Level 1 — Holistic Judge
 
One LLM call assesses the **entire batch** as a whole against the source context.
 
| Outcome | Action |
|---------|--------|
| `PASS` | Advance to Level 2 scoring |
| `FAIL` (attempts < `MAX_L1_REGEN`) | Clear dataset, reset `qa_generation_hits`, regenerate from scratch |
| `FAIL` (attempts = `MAX_L1_REGEN`) | Accept as-is and advance to Level 2 |
 
### Level 2 — Per-Pair Metric Scoring
 
One LLM call scores every pair on **5 RAG quality dimensions** (each 0 – 1):
 
| Metric | What it measures |
|--------|-----------------|
| `faithfulness` | Answer is fully supported by the source context |
| `answer_relevancy` | Answer directly addresses the question |
| `context_relevancy` | Question is relevant to the retrieved context |
| `context_precision` | Context contains no irrelevant noise for the question |
| `context_utilization` | Answer makes good use of available context |
 
**Filtering rule:** if a pair's average across all 5 metrics falls below `LEVEL2_THRESHOLD` (0.6), the pair is dropped. Surviving pairs are written back to `generated_dataset` for persist.
 
Multi-hop pairs go through an equivalent holistic judge before persist (no Level 2 filtering).
 
---
 
## LLM Configuration
 
Configure the LLM via environment variables in a `.env` file at the project root:
 
```env
LLM_MODEL=openrouter/meta-llama/llama-3.3-70b-instruct
LLM_API_KEY=sk-or-v1-...
LLM_MAX_TOKENS=1024
LLM_TEMPERATURE=0.7
OPIK_API_KEY=...
DATABASE_URL=postgresql://user:password@localhost:5432/synthetic_dataset
```
 
| Variable | Default | Description |
|----------|---------|-------------|
| `LLM_MODEL` | `openrouter/meta-llama/llama-3.3-70b-instruct` | Any litellm-compatible model string |
| `LLM_API_KEY` | — | API key for the chosen provider |
| `LLM_MAX_TOKENS` | `1024` | Max tokens per LLM response |
| `LLM_TEMPERATURE` | `0.7` | Base generation temperature (increases slightly on later hits) |
| `OPIK_API_KEY` | — | Opik tracing key (optional; tracing is disabled if absent) |
| `DATABASE_URL` | — | PostgreSQL connection string |
 
```env
# Use any OpenRouter model
LLM_MODEL=openrouter/google/gemini-pro
LLM_API_KEY=sk-or-v1-...
```
 
> **Note:** `LLM_MAX_TOKENS` defaults to `1024` to stay within OpenRouter free-tier credit limits. Set it higher in `.env` once you have sufficient credits (e.g. `LLM_MAX_TOKENS=2048`).
 
---
 
## Contributing
 
1. Fork the repository and create a feature branch.
2. Install dev dependencies: `pip install -r requirements.txt`
3. Run tests: `pytest tests/`
4. Open a pull request against `updated_architecture`.
 
---
 
## License
 
MIT — see [LICENSE](LICENSE) for details.
