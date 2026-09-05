#!/bin/bash
# klass L2 블랙박스 QA — test-plan.md 의 TC 를 순서대로 실행한다.
# H2 인메모리라 서버 기동 직후 1회 실행을 전제로 한 순서 의존 시나리오다.
BASE=http://localhost:8080
PASS=0; FAIL=0; RESULTS=""
EVIDENCE="${EVIDENCE:-/tmp/l2-evidence.log}"
: > "$EVIDENCE"
{ echo "# klass L2 블랙박스 QA — 실행 증적 (요청·응답 원문)"
  echo "# 실행 시각: $(date '+%Y-%m-%d %H:%M:%S %Z')"
  echo "# 대상: $BASE · git $(git -C /Users/chalsll/Work/klass rev-parse --short HEAD 2>/dev/null)"
  echo "# 토큰은 앞 24자만 기록한다 (JWT 전문은 증적 가치 대비 노이즈)"
  echo; } >> "$EVIDENCE"
CUR_TC=""

jqget() { python3 -c "import json,sys
d=json.load(sys.stdin)
cur=d
for k in sys.argv[1].split('.'):
    if cur is None: break
    cur=cur.get(k) if isinstance(cur,dict) else (cur[int(k)] if isinstance(cur,list) and k.lstrip('-').isdigit() and int(k)<len(cur) else None)
print(cur)" "$1" 2>/dev/null; }

# call METHOD PATH TOKEN BODY -> sets STATUS, BODY_OUT
call() {
  local method=$1 path=$2 token=$3 body=$4
  local args=(-s -o /tmp/l2body -w '%{http_code}' -X "$method" "$BASE$path" -H 'Content-Type: application/json')
  [ -n "$token" ] && args+=(-H "Authorization: Bearer $token")
  [ -n "$body" ] && args+=(-d "$body")
  STATUS=$(curl "${args[@]}")
  BODY_OUT=$(cat /tmp/l2body)
  { echo "[$(date '+%H:%M:%S')] --> $method $path"
    [ -n "$token" ] && echo "    Authorization: Bearer ${token:0:24}..."
    [ -n "$body" ] && echo "    RequestBody: $body"
    echo "    <-- HTTP $STATUS"
    echo "    ResponseBody: $BODY_OUT"
  } >> "$EVIDENCE"
}

# check TC-ID 기대상태 [기대에러코드] [필드경로=기대값 ...]
check() {
  local tc=$1 expst=$2; shift 2
  local ok=1 detail="HTTP $STATUS"
  [ "$STATUS" != "$expst" ] && ok=0 && detail="HTTP $STATUS (기대 $expst)"
  for cond in "$@"; do
    local path="${cond%%=*}" want="${cond#*=}"
    local got=$(echo "$BODY_OUT" | jqget "$path")
    if [ "$want" = "__exists__" ]; then
      [ "$got" = "None" -o -z "$got" ] && ok=0 && detail="$detail; $path 없음"
    elif [ "$got" != "$want" ]; then
      ok=0; detail="$detail; $path=$got (기대 $want)"
    fi
  done
  if [ $ok = 1 ]; then PASS=$((PASS+1)); RESULTS+="PASS  $tc  $detail"$'\n'
  else FAIL=$((FAIL+1)); RESULTS+="FAIL  $tc  $detail  body=$(echo $BODY_OUT | head -c 300)"$'\n'; fi
  echo "    ==[ $tc: $([ $ok = 1 ] && echo PASS || echo FAIL) — $detail ]==" >> "$EVIDENCE"
  echo >> "$EVIDENCE"
}

login() { # username -> access refresh
  call POST /v1/auth/login '' "{\"username\":\"$1\",\"password\":\"$2\"}"
}

########## AUTH ##########
login chals test
check AUTH-01 200 data.accessToken=__exists__ data.refreshToken=__exists__
CHALS_AT=$(echo "$BODY_OUT" | jqget data.accessToken)
CHALS_RT=$(echo "$BODY_OUT" | jqget data.refreshToken)

call POST /v1/auth/login '' '{"username":"chals","password":"wrong"}'
check AUTH-02 401 error.code=INVALID_CREDENTIALS
A2_BODY_CODE=$(echo "$BODY_OUT" | jqget error.code); A2_MSG=$(echo "$BODY_OUT" | jqget error.message)

call POST /v1/auth/login '' '{"username":"ghost-nobody","password":"wrong"}'
check AUTH-03 401 error.code=INVALID_CREDENTIALS "error.message=$A2_MSG"

call POST /v1/auth/reissue '' "{\"refreshToken\":\"$CHALS_RT\"}"
check AUTH-04 200 data.accessToken=__exists__
CHALS_AT2=$(echo "$BODY_OUT" | jqget data.accessToken)
CHALS_RT2=$(echo "$BODY_OUT" | jqget data.refreshToken)

# 폐기된 RT 재사용 → 거부 + 전체 무효화
call POST /v1/auth/reissue '' "{\"refreshToken\":\"$CHALS_RT\"}"
check AUTH-05a 401 error.code=REFRESH_TOKEN_REUSED
call POST /v1/auth/reissue '' "{\"refreshToken\":\"$CHALS_RT2\"}"
check AUTH-05b 401   # 전체 무효화로 새 RT 도 거부 (코드는 REVOKED/REUSED 계열)

# 로그아웃 후 AT 사용
login chals test; CHALS_AT=$(echo "$BODY_OUT" | jqget data.accessToken); CHALS_RT=$(echo "$BODY_OUT" | jqget data.refreshToken)
call POST /v1/auth/logout "$CHALS_AT" "{\"refreshToken\":\"$CHALS_RT\"}"
LOGOUT_ST=$STATUS
call GET /v1/users/me "$CHALS_AT" ''
check AUTH-06 401 error.code=TOKEN_REVOKED

login chals test; CHALS_AT=$(echo "$BODY_OUT" | jqget data.accessToken); CHALS_RT=$(echo "$BODY_OUT" | jqget data.refreshToken)
call GET /v1/users/me "$CHALS_RT" ''
check AUTH-07 401 error.code=TOKEN_TYPE_MISMATCH

call GET /v1/users/me '' ''
check AUTH-08 401 error.code=UNAUTHENTICATED

call GET /v1/users/me "$CHALS_AT" ''
check USER-01 200 data.username=chals

########## KLASS ##########
login creator test; CR_AT=$(echo "$BODY_OUT" | jqget data.accessToken)
login chals2 test; C2_AT=$(echo "$BODY_OUT" | jqget data.accessToken)

KBODY='{"title":"QA 강의","description":"L2 QA 용","capacity":1,"price":10000,"startsOn":"2026-10-01","endsOn":"2026-10-31","cancellationPeriodDays":7}'
call POST /v1/klasses "$CR_AT" "$KBODY"
check KLASS-01 201 data.status=DRAFT
K1=$(echo "$BODY_OUT" | jqget data.id)

call POST /v1/klasses "$CHALS_AT" "$KBODY"
check KLASS-02 403

call GET /v1/klasses '' ''
DRAFT_VISIBLE=$(echo "$BODY_OUT" | python3 -c "import json,sys; d=json.load(sys.stdin); items=d['data'] if isinstance(d['data'],list) else d['data'].get('items',d['data'].get('content',[])); print(any(i.get('status')=='DRAFT' for i in items))" 2>/dev/null)
STATUS_SAVED=$STATUS
[ "$STATUS_SAVED" = 200 ] && [ "$DRAFT_VISIBLE" = "False" ] && { PASS=$((PASS+1)); RESULTS+="PASS  KLASS-03  HTTP 200, DRAFT 미노출"$'\n'; } || { FAIL=$((FAIL+1)); RESULTS+="FAIL  KLASS-03  HTTP $STATUS_SAVED draftVisible=$DRAFT_VISIBLE"$'\n'; }

call GET /v1/klasses/$K1 "$CHALS_AT" ''
check KLASS-04 404 error.code=KLASS_NOT_FOUND

call GET /v1/klasses/$K1 "$CR_AT" ''
check KLASS-05 200 data.status=DRAFT

call POST /v1/klasses "$CR_AT" '{"title":"bad","description":"x","capacity":0,"price":10000,"startsOn":"2026-10-01","endsOn":"2026-10-31"}'
check KLASS-06 400 error.code=VALIDATION_ERROR

call PATCH /v1/klasses/$K1/status "$CR_AT" '{"status":"OPEN"}'
check KLASS-07 200 data.status=OPEN

call PATCH /v1/klasses/$K1/status "$CHALS_AT" '{"status":"CLOSED"}'
check KLASS-08 403

########## ENROLLMENT (capacity=1 강의 K1 사용) ##########
call POST /v1/klasses/$K1/enrollments "$CHALS_AT" ''
check ENR-01 201 data.status=PENDING data.source=DIRECT data.expiresAt=__exists__
E1=$(echo "$BODY_OUT" | jqget data.id)

# OPEN→DRAFT 신청자 존재 → 거부
call PATCH /v1/klasses/$K1/status "$CR_AT" '{"status":"DRAFT"}'
check KLASS-09 409 error.code=INVALID_KLASS_STATUS_TRANSITION

# DRAFT 강의 신청 → 409 (정본: status != OPEN → KLASS_NOT_OPEN. 404 은닉은 조회 전용)
call POST /v1/klasses "$CR_AT" "$KBODY"
K2=$(echo "$BODY_OUT" | jqget data.id)
call POST /v1/klasses/$K2/enrollments "$CHALS_AT" ''
check ENR-02 409 error.code=KLASS_NOT_OPEN

call POST /v1/klasses/$K1/enrollments "$CHALS_AT" ''
check ENR-03 409 error.code=DUPLICATE_ENROLLMENT

call POST /v1/klasses/$K1/enrollments "$CR_AT" ''
check ENR-04 403 error.code=SELF_ENROLLMENT_FORBIDDEN

call POST /v1/klasses/$K1/enrollments "$C2_AT" ''
check ENR-05 409 error.code=KLASS_CAPACITY_FULL

call POST /v1/enrollments/$E1/confirm "$C2_AT" ''
check ENR-07 403 error.code=NOT_ENROLLMENT_OWNER

call POST /v1/enrollments/$E1/confirm "$CHALS_AT" ''
check ENR-06 200 data.status=CONFIRMED

call POST /v1/enrollments/$E1/confirm "$CHALS_AT" ''
check ENR-08 409 error.code=INVALID_ENROLLMENT_STATUS_TRANSITION

call POST /v1/enrollments/$E1/cancel "$C2_AT" ''
check ENR-10 403 error.code=NOT_ENROLLMENT_OWNER

call GET /v1/enrollments/$E1 "$C2_AT" ''
ENR16_ST=$STATUS; ENR16_CODE=$(echo "$BODY_OUT" | jqget error.code)
if [ "$ENR16_ST" = 403 -o "$ENR16_ST" = 404 ]; then PASS=$((PASS+1)); RESULTS+="PASS  ENR-16  HTTP $ENR16_ST $ENR16_CODE"$'\n'; else FAIL=$((FAIL+1)); RESULTS+="FAIL  ENR-16  HTTP $ENR16_ST"$'\n'; fi

########## WAITLIST (K1 만석 상태) ##########
call POST /v1/klasses/$K1/waitlists "$C2_AT" ''
check WL-01 201 data.status=WAITING data.position=1
W1=$(echo "$BODY_OUT" | jqget data.id)

call POST /v1/klasses/$K1/waitlists "$C2_AT" ''
check WL-03 409 error.code=DUPLICATE_WAITLIST

call POST /v1/klasses/$K1/waitlists "$CHALS_AT" ''
check WL-04 409 error.code=DUPLICATE_ENROLLMENT

call POST /v1/waitlists/$W1/cancel "$CHALS_AT" ''
check WL-08 403 error.code=NOT_WAITLIST_OWNER

# 취소 → 승격: chals 취소 → chals2 승격
call POST /v1/enrollments/$E1/cancel "$CHALS_AT" ''
check ENR-09 200 data.status=CANCELLED

call GET /v1/waitlists/me "$C2_AT" ''
WL_ST=$(echo "$BODY_OUT" | python3 -c "import json,sys; d=json.load(sys.stdin); items=d['data'] if isinstance(d['data'],list) else d['data'].get('items',d['data'].get('content',[])); print(items[0].get('status') if items else None)" 2>/dev/null)
[ "$WL_ST" = "PROMOTED" ] && { PASS=$((PASS+1)); RESULTS+="PASS  WL-05a  대기 상태 PROMOTED"$'\n'; } || { FAIL=$((FAIL+1)); RESULTS+="FAIL  WL-05a  대기 상태=$WL_ST (기대 PROMOTED)"$'\n'; }

call GET /v1/enrollments/me "$C2_AT" ''
PROM=$(echo "$BODY_OUT" | python3 -c "import json,sys; d=json.load(sys.stdin); items=d['data'] if isinstance(d['data'],list) else d['data'].get('items',d['data'].get('content',[])); e=[i for i in items if i.get('source')=='WAITLIST']; print(e[0]['status'] if e else None); print(e[0]['id'] if e else '')" 2>/dev/null)
PROM_STATUS=$(echo "$PROM" | sed -n 1p); E2=$(echo "$PROM" | sed -n 2p)
[ "$PROM_STATUS" = "PENDING" ] && { PASS=$((PASS+1)); RESULTS+="PASS  WL-05b  승격 enrollment PENDING/WAITLIST 생성"$'\n'; } || { FAIL=$((FAIL+1)); RESULTS+="FAIL  WL-05b  승격 enrollment=$PROM_STATUS"$'\n'; }

# 좌석 순변화 0 → K1 여전히 만석: chals 재신청 시 CAPACITY_FULL 이어야 함 (동시에 ENR-12 재신청 경로는 항 아래에서 검증)
call POST /v1/klasses/$K1/enrollments "$CHALS_AT" ''
check WL-05c 409 error.code=KLASS_CAPACITY_FULL

call POST /v1/waitlists/$W1/cancel "$C2_AT" ''
check WL-06 409 error.code=WAITLIST_NOT_WAITING

# 승격된 chals2 의 PENDING 취소 → 좌석 반납(대기자 없음) → chals 재신청 성공 (ENR-12)
call POST /v1/enrollments/$E2/cancel "$C2_AT" ''
ENR12_PRE=$STATUS
call POST /v1/klasses/$K1/enrollments "$CHALS_AT" ''
check ENR-12 201 data.status=PENDING
E3=$(echo "$BODY_OUT" | jqget data.id)

# CANCELLED 재확정/재취소 (E1 은 CANCELLED)
call POST /v1/enrollments/$E1/confirm "$CHALS_AT" ''
check ENR-11a 409 error.code=INVALID_ENROLLMENT_STATUS_TRANSITION
call POST /v1/enrollments/$E1/cancel "$CHALS_AT" ''
check ENR-11b 409 error.code=INVALID_ENROLLMENT_STATUS_TRANSITION

# WL: 만석 K1 에 chals2 대기 등록 → 포기 → 재대기 (WL-07, WL-09)
call POST /v1/klasses/$K1/waitlists "$C2_AT" ''
check WL-09pre 201 data.status=WAITING
W2=$(echo "$BODY_OUT" | jqget data.id)
call POST /v1/waitlists/$W2/cancel "$C2_AT" ''
check WL-07 200 data.status=CANCELLED
call POST /v1/klasses/$K1/waitlists "$C2_AT" ''
check WL-09 201 data.status=WAITING
W3=$(echo "$BODY_OUT" | jqget data.id)

# 자리 있는 강의 대기 등록 → 409 (새 OPEN 강의 K3, capacity 2)
call POST /v1/klasses "$CR_AT" '{"title":"QA 강의 3","description":"자리 있음","capacity":2,"price":0,"startsOn":"2026-10-01","endsOn":"2026-10-31"}'
K3=$(echo "$BODY_OUT" | jqget data.id)
call PATCH /v1/klasses/$K3/status "$CR_AT" '{"status":"OPEN"}'
call POST /v1/klasses/$K3/waitlists "$C2_AT" ''
check WL-02 409 error.code=WAITLIST_SEAT_AVAILABLE

########## CLOSED 시나리오 ##########
# K1: chals PENDING(E3), chals2 WAITING(W3) 상태에서 CLOSED 전환
call PATCH /v1/klasses/$K1/status "$CR_AT" '{"status":"CLOSED"}'
check KLASS-CLOSE 200 data.status=CLOSED

# 잔여 대기 일괄 정리 확인
call GET /v1/waitlists/me "$C2_AT" ''
WL10=$(echo "$BODY_OUT" | python3 -c "import json,sys; d=json.load(sys.stdin); items=d['data'] if isinstance(d['data'],list) else d['data'].get('items',d['data'].get('content',[])); w=[i for i in items if i.get('id')==$W3]; print(w[0]['status'] if w else None)" 2>/dev/null)
[ "$WL10" = "CANCELLED" ] && { PASS=$((PASS+1)); RESULTS+="PASS  WL-10  CLOSED 전환 시 잔여 대기 CANCELLED"$'\n'; } || { FAIL=$((FAIL+1)); RESULTS+="FAIL  WL-10  대기 상태=$WL10"$'\n'; }

# CLOSED 신규 신청 거부
call POST /v1/klasses/$K1/enrollments "$C2_AT" ''
check ENR-13 409 error.code=KLASS_NOT_OPEN

# CLOSED 전환 후 기존 PENDING 결제 성공
call POST /v1/enrollments/$E3/confirm "$CHALS_AT" ''
check ENR-14 200 data.status=CONFIRMED

# CLOSED → OPEN / CLOSED → DRAFT 거부
call PATCH /v1/klasses/$K1/status "$CR_AT" '{"status":"OPEN"}'
check KLASS-10 409 error.code=INVALID_KLASS_STATUS_TRANSITION
call PATCH /v1/klasses/$K1/status "$CR_AT" '{"status":"DRAFT"}'
check KLASS-11 409 error.code=INVALID_KLASS_STATUS_TRANSITION

########## 목록·수강생 ##########
call GET /v1/enrollments/me "$CHALS_AT" ''
OTHER=$(echo "$BODY_OUT" | python3 -c "import json,sys; d=json.load(sys.stdin); items=d['data'] if isinstance(d['data'],list) else d['data'].get('items',d['data'].get('content',[])); print(len(items)>0)" 2>/dev/null)
[ "$STATUS" = 200 -a "$OTHER" = "True" ] && { PASS=$((PASS+1)); RESULTS+="PASS  ENR-15  본인 신청 목록 조회"$'\n'; } || { FAIL=$((FAIL+1)); RESULTS+="FAIL  ENR-15  HTTP $STATUS items>0=$OTHER"$'\n'; }

call GET /v1/klasses/$K1/enrollments "$CHALS_AT" ''
check KLASS-12 403

call GET /v1/klasses/$K1/enrollments "$CR_AT" ''
check KLASS-13 200

########## DOC ##########
call GET /docs/openapi3.json '' ''
NPATHS=$(echo "$BODY_OUT" | python3 -c "import json,sys; print(len(json.load(sys.stdin)['paths']))" 2>/dev/null)
[ "$STATUS" = 200 -a "$NPATHS" = 16 ] && { PASS=$((PASS+1)); RESULTS+="PASS  DOC-01  openapi3.json 유효, paths 16/ops 19"$'\n'; } || { FAIL=$((FAIL+1)); RESULTS+="FAIL  DOC-01  HTTP $STATUS paths=$NPATHS"$'\n'; }

call GET /docs/api-guide.html '' ''
D1=$STATUS
call GET /docs/api-test.html '' ''
[ "$D1" = 200 -a "$STATUS" = 200 ] && { PASS=$((PASS+1)); RESULTS+="PASS  DOC-02  문서 페이지 서빙"$'\n'; } || { FAIL=$((FAIL+1)); RESULTS+="FAIL  DOC-02  guide=$D1 test=$STATUS"$'\n'; }

echo "$RESULTS"
echo "=== L2 결과: PASS $PASS / FAIL $FAIL (총 $((PASS+FAIL))) ==="
echo "(logout status: $LOGOUT_ST, ENR12 사전취소: $ENR12_PRE)"
