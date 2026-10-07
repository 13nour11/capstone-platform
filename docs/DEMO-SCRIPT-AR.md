# سكريبت الديمو — Capstone E-Commerce Platform (15 دقيقة)

> كل خطوة فيها: **مين يقدّم** · **تقول إيه** · **تعمل إيه على الشاشة** · **المفروض يظهر إيه**.
> التقسيم المقترح حسب الملكية: **A** (Nourhan) الأمان و k6 · **B** (Mohammed) المنصة والـ Saga والـ trace · **C** (Doaa) الفشل والدفع والنشر والبونص.

---

## قبل الديمو (ساعة قبلها) — مش بيتعرض

```bash
# 1) من جذر الريبو، على main
git checkout main && git pull
cp -n .env.example .env          # ولو أول مرة: غيّري كل change-me
#    اتأكدي إن PAYMENT_FAILURE_RATE=0.0 في .env

# 2) شغّلي كل حاجة
docker compose --env-file .env -f deployment/docker/docker-compose.yml up -d --build
scripts/verify-l0.sh             # لازم يطلع: L0 GREEN

# 3) سخّني النظام: اعملي طلب أو اتنين قبل الديمو (أول طلب على JVM بارد بيبقى بطيء)
scripts/e2e-check.sh             # لازم يطلع: E2E GREEN 39/39
```

**افتحي التابات دي في المتصفح وسيبيها جاهزة:**

| تاب | العنوان |
|---|---|
| Eureka | http://localhost:8761 |
| Zipkin | http://localhost:9411 |
| Grafana (سجّلي دخول قبلها) | http://localhost:3000 → *Order Analytics* |
| GitHub Actions (آخر run أخضر على main) | https://github.com/13nour11/capstone-platform/actions |
| الـ slides | `docs/slides/capstone-final.pptx` |

**افتحي 2 terminal.** في الأول اعملي الدوال دي (من غير `set -a`، شرحها تحت في "فخ مهم"):

```bash
GW=http://localhost:8080
KC=http://localhost:8180/realms/ecommerce-platform/protocol/openid-connect/token
PW=$(grep ^KC_TEST_USER_PASSWORD= .env | cut -d= -f2)
token() { curl -s "$KC" -d grant_type=password -d client_id=api-gateway -d username="$1" -d password="$PW" \
          | sed -E 's/.*"access_token":"([^"]+)".*/\1/'; }
refresh() { ADMIN=$(token admin); CUSTOMER=$(token customer1); echo "tokens ok"; }
order() { curl -s -X POST $GW/api/v1/orders -H "Authorization: Bearer $CUSTOMER" -H 'Content-Type: application/json' \
          -d "{\"items\":[{\"productId\":$1,\"quantity\":$2}]}"; echo; }
status() { curl -s $GW/api/v1/orders/$1 -H "Authorization: Bearer $CUSTOMER" | grep -o '"status":"[A-Z]*"'; }
DC="docker compose --env-file .env -f deployment/docker/docker-compose.yml"
refresh
```

> ⚠️ **الـ token بيعيش 5 دقايق بس.** اكتبي `refresh` في أول كل قسم.

> ⚠️ **فخ مهم:** ماتعمليش `set -a; . ./.env` في الـ terminal. لو عملتيها، `PAYMENT_FAILURE_RATE=0.0` هيفضل في الـ shell
> ويغلب على `.env`، وسيناريو الفشل مش هيشتغل قدام الدكتور (حصلت معانا في التجربة). لهذا بنمرّر القيمة صريحة في الأمر تحت.

---

## 0–2 دقيقة · المقدمة — **A**

**تقول:**
> "عملنا متجر إلكتروني بنظام microservices: 9 خدمات، كل خدمة ليها قاعدة بيانات لوحدها، وبيكلموا بعض بـ REST لما يحتاجوا رد فوري،
> وبـ Kafka events للباقي. الطلب بيكمل عن طريق **Saga بالـ choreography**: مفيش خدمة مركزية بتدير، كل خدمة بتسمع للحدث وترد بحدث."

**على الشاشة:** سلايد المعمارية + رسمة `docs/architecture/02-order-sequence.svg`.

---

## 2–4 دقيقة · المنصة شغالة — **B**

```bash
scripts/verify-l0.sh
```
**يظهر:** كل البنية التحتية `healthy`، config-server بيقدّم الإعدادات، وكل الخدمات `registered and UP` ← **L0 GREEN**.

**على الشاشة:** تاب Eureka — كل الخدمات مسجّلة.

**تقول:** "أمر واحد بيشغّل كل حاجة (`docker compose up`)، والسكريبت ده بيثبت إن المنصة سليمة قبل أي حاجة."

---

## 4–6 دقيقة · الأمان — **A**

```bash
refresh
curl -i "$GW/api/v1/products?page=0&size=5"                                   # 200: التصفح من غير login
curl -i -X POST $GW/api/v1/products -H 'Content-Type: application/json' \
     -d '{"name":"Desk Mat","price":19.90,"categoryId":3}'                     # 401: من غير token
curl -i -X POST $GW/api/v1/products -H "Authorization: Bearer $CUSTOMER" \
     -H 'Content-Type: application/json' -d '{"name":"Desk Mat","price":19.90,"categoryId":3}'   # 403: عميل مش أدمن
curl -s -Z --parallel-max 100 -w '\n%{http_code}\n' $(printf "$GW/api/v1/products/1 %.0s" $(seq 100)) \
  | grep -E '^[0-9]{3}$' | sort | uniq -c                                       # 200 و 429: rate limit
```

**تقول:** "الـ gateway هي الباب الوحيد: بتتحقق من الـ token من Keycloak، وبتطبّق الصلاحيات، وبتحدد عدد الطلبات لكل عميل.
وكمان خدمة الطلبات بتكلّم خدمة المخزون بـ token خاص بيها هي كخدمة (FR-14)، مش بالـ token بتاع العميل."

---

## 6–8 دقيقة · الطلب الناجح (Happy path) — **B**

```bash
refresh
order 1 1                     # 201 و "status":"PENDING" — انسخي الـ orderId
ID=<الصقي الـ orderId هنا>
sleep 2; status $ID           # "status":"CONFIRMED"
$DC logs notification-service | grep "$ID" | tail -1    # NOTIFY ... your order is CONFIRMED
```

**تقول (وانتي بتستني الثانيتين):**
> "الرد رجع فوراً **PENDING**. في الخلفية: الطلب اتحفظ مع حدث `OrderPlaced` في **نفس الـ transaction** (Outbox)،
> المخزون حجز الكمية، الدفع خصم الفلوس، الطلب بقى **CONFIRMED**، والعميل وصله إشعار."

---

## 8–10 دقيقة · الفشل والتعويض (Compensation) — **C** (Doaa)

**1) مفيش مخزون:**
```bash
refresh
order 1 100000                # 409 OUT_OF_STOCK — ومفيش طلب اتعمل ومفيش دفع
```

**2) الدفع اترفض ← الطلب يتلغي والمخزون يرجع:**
```bash
PAYMENT_FAILURE_RATE=1.0 $DC up -d payment-service      # القيمة صريحة هنا (شوفي "فخ مهم")
#   استني ~30 ثانية لحد ما يبقى healthy:  $DC ps payment-service
curl -s $GW/api/v1/inventory/2 -H "Authorization: Bearer $ADMIN"     # لاحظي "available"
order 2 1                     # PENDING — انسخي الـ ID
sleep 3; status $ID           # "status":"CANCELLED"
curl -s $GW/api/v1/inventory/2 -H "Authorization: Bearer $ADMIN"     # "available" رجع نفس الرقم
$DC logs notification-service | grep "$ID" | tail -1    # ... your order is CANCELLED reason=Payment declined
```

**3) لا مخزون معلّق (NFR-05) — لازم يطبع 0:**
```bash
$DC exec postgres psql -U postgres -d inventory_db -tAc \
 "SELECT count(*) FROM reservation r WHERE r.status='RESERVED' AND r.order_id IN (SELECT order_id FROM cancelled_order) AND r.created_at < now() - interval '30 seconds';"
```

**4) رجّعي الدفع طبيعي (مهم قبل اللي بعده):**
```bash
PAYMENT_FAILURE_RATE=0.0 $DC up -d payment-service
```

**تقول:**
> "مفيش rollback موزّع: كل خدمة بتعمل **عكس** خطوتها لما تسمع حدث الفشل. المخزون رجّع الكمية، الطلب اتلغى، والعميل اتبلّغ.
> ولو نفس الحدث وصل مرتين، جدول `processed_event` بيمنع التكرار، يعني الفلوس عمرها ما تتخصم مرتين."

**لو فاضل وقت (اختياري، 45 ثانية) — الدفع واقع خالص (NFR-01):**
```bash
$DC stop payment-service ; order 3 1 ; ID=<...> ; sleep 5 ; status $ID     # PENDING ويفضل PENDING
$DC start payment-service ; sleep 20 ; status $ID                           # CONFIRMED — ولا حاجة ضاعت
```

---

## 10–11 دقيقة · trace واحد عبر كل الخدمات — **B**

```bash
$DC logs order-service | grep 'Order placed' | tail -1     # انسخي قيمة "traceId"
```
**على الشاشة:** Zipkin → الصقي الـ traceId → يظهر:
gateway → order → inventory/product → Kafka → inventory → payment → order → notification.

**تقول:** "الـ `traceparent` بيتحفظ في جدول الـ outbox ويتبعت كـ header في Kafka، فالطلب كله ليه **traceId واحد**، حتى عبر الرسائل."

---

## 11–12 دقيقة · النشر — **C** (Doaa)

**على الشاشة:**
- GitHub Actions: آخر run أخضر على `main` → اختبارات + coverage ≥ 60% + gitleaks + بناء الـ images ونشرها على GHCR.
- **لو الـ cluster شغال على لابتوب:** `kubectl get pods -n ecommerce` (كله Running)، و ArgoCD: كله **Synced / Healthy**.
  ولو فيه وقت: `kubectl scale deploy/product-service --replicas=0 -n ecommerce` ← ArgoCD يرجّعه لوحده (self-heal).
- **لو مفيش cluster:** سلايد 16 (ArgoCD 10/10 Synced/Healthy) وسلايد 17 (CI أخضر + Grafana)، من التشغيل على `main` يوم 7 أكتوبر.
- **ArgoCD UI محلي:** الـ repo private، فـ ArgoCD بيقرا `env/dev` من git server محلي (`deployment/argocd/README.md`). الـ UI على `https://localhost:8443` (`kubectl -n argocd port-forward svc/argocd-server 8443:443`). لو برنامج الحماية بيمنع الشهادة المحلية، اعملوا ArgoCD يشتغل HTTP (الأوامر في `deployment/argocd/README.md`) وافتحوا `http://localhost:8090`.

**تقول:** "كل image بتشتغل بيوزر عادي مش root، والـ secrets مش في Git: بتتعمل بـ `scripts/create-k8s-secrets.sh`. Helm chart واحد لكل الخدمات، و ArgoCD بيسحب من Git."

---

## 12–13 دقيقة · اختبار الأحمال k6 — **A**

**على الشاشة:** `docs/PERFORMANCE-REPORT.md` §4.2 و §5.

**تقول:**
> "لقينا عنق زجاجة: كل طلب كان بيكلم product-service عشان السعر. حطينا cache للأسعار 60 ثانية،
> و`POST /orders` p95 نزل من **600ms لـ 443ms (−26%)**، والطلبات اللي كانت بتقع من الجدول نزلت من **69 لـ 0**.
> و`GET /products` ماتغيرش، ودي الـ control اللي بتثبت إن التحسن من الـ fix مش صدفة."

---

## 13–15 دقيقة · البونص — **C** (Doaa)

**B2 — تحليلات الطلبات (البونص الأساسي):**
```bash
refresh
curl -s "$GW/api/v1/analytics/summary?hours=24" -H "Authorization: Bearer $ADMIN"
curl -s -o /dev/null -w '%{http_code}\n' "$GW/api/v1/analytics/summary" -H "Authorization: Bearer $CUSTOMER"   # 403: للأدمن بس
```
**على الشاشة:** Grafana → *Order Analytics*: الطلبات في الدقيقة حسب الحالة، الإيرادات، نسبة فشل الـ Saga
(هتلاقي الطلبات اللي اتعملت في الديمو، ومنها الـ CANCELLED).

**تقول:** "مستهلك منفصل بيقرا أحداث الطلبات ويبني read model خاص بالتقارير (CQRS)، بدون أي infrastructure جديدة.
ولو الحدث اتكرر مش بيتحسب مرتين."

**الإضافات (لو فيه وقت، 20 ثانية لكل واحد):**
```bash
# B1 تقييم ← متوسط التقييم يتحدث
curl -s -X POST $GW/api/v1/products/1/reviews -H "Authorization: Bearer $CUSTOMER" \
     -H 'Content-Type: application/json' -d '{"rating":5,"comment":"Great"}' ; sleep 2
curl -s $GW/api/v1/products/1 | grep -o '"averageRating":[^,]*,"ratingCount":[0-9]*'
# B4 تنبيه نقص المخزون — في الـ terminal التاني اتفتح الأول:
#   curl -N $GW/api/v1/alerts/stream -H "Authorization: Bearer <ADMIN token>"
curl -s -X PUT $GW/api/v1/inventory/2 -H "Authorization: Bearer $ADMIN" \
     -H 'Content-Type: application/json' -d '{"availableQuantity":3}'      # التنبيه يظهر في التاني خلال < 2 ثانية
```

**الختام (A):** "331 test أخضر، E2E 39/39، و CI أخضر على main. شكراً — جاهزين للأسئلة."

---

## لو حاجة باظت قدام الدكتور (Plan B)

| المشكلة | الحل السريع |
|---|---|
| `401` في أي أمر | الـ token خلص: اكتبي `refresh` |
| الطلب فضل `PENDING` في الـ happy path | استني 5 ثواني كمان؛ لو لسه: `$DC ps` واتأكدي إن payment و inventory `healthy` |
| سيناريو الدفع طلع `CONFIRMED` بدل `CANCELLED` | الـ shell فيه `PAYMENT_FAILURE_RATE` قديم: نفّذي الأمر بالقيمة الصريحة زي ما فوق، واتأكدي: `$DC exec payment-service env \| grep FAILURE` |
| خدمة مش طالعة | `$DC logs <service> \| tail -30`، وبعدها `$DC up -d <service>` |
| كل حاجة واقعة | اعرضي الـ CI run الأخضر + نتيجة `e2e-check.sh` 39/39 + الـ screenshots |

## أسئلة متوقعة (كل واحد لازم يعرف يجاوبها)

1. **ليه choreography مش orchestration؟** خطوات قليلة وخطية؛ لو فيه فروع وقواعد كتير كنا هنحوّل لـ orchestrator (ADD Decision 5-1).
2. **إزاي الحدث مايضيعش؟** Outbox: الطلب والحدث في نفس الـ transaction، وبعدين poller يبعته على Kafka.
3. **لو الحدث وصل مرتين؟** `processed_event` + unique constraint ← التاني مالوش أي تأثير.
4. **لو consumer فشل على طول؟** 3 محاولات بعدين `<topic>.DLT` + سطر `ALERT` في اللوج.
5. **ليه الـ stock check برا الـ transaction؟** مفيش network I/O جوه transaction قاعدة البيانات، عشان مانمسكش connections.
6. **ليه الـ sweeper بيرجّع المخزون حسب نتيجة الـ Saga مش حسب العمر؟** عشان الدفع البطيء مايخليش نفس القطعة تتباع مرتين (Decision 6-1).
7. **الـ secrets فين؟** في `.env` (مش في Git) وفي Kubernetes Secrets، و gitleaks في الـ CI بيمنع أي تسريب.
