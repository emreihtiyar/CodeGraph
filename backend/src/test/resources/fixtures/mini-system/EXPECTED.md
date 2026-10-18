# Mini Sistem: Beklenen Sonuçlar (referans akışlar)

Bu dosya, çıkarıcı, linker ve sorgu testlerinin doğrulandığı **elle hazırlanmış doğru cevaptır**
(PRD §2.3, "10 referans akış"). Fixture değişirse bu dosya da güncellenmelidir.

```
leasing-web (FRONTEND)
  ├─▶ leasing-credit-bff (BFF, gateway: /leasing-credit-bff)
  │     ├─▶ credit-api (API, context-path: /credit-api) ─▶ core-adapter (ADAPTER)
  │     └─▶ sales-api  (API)                           ─▶ core-adapter
  └─▶ scoring-bff (BFF, gateway: /scoring)
        ├─▶ credit-api
        ├─▶ sales-api
        └─▶ legacy-score-service (analizde yok → UNRESOLVED)
```

## Endpoint'ler (path, context-path dahil; normalize: `{*}`)

| Servis             | Metot | Path                                   | Handler                                  |
|--------------------|-------|----------------------------------------|------------------------------------------|
| leasing-credit-bff | GET   | /applications/{*}                      | CreditApplicationController.getApplication |
| leasing-credit-bff | POST  | /applications                          | CreditApplicationController.createApplication |
| scoring-bff        | GET   | /scores/{*}                            | ScoreController.getScore                 |
| scoring-bff        | GET   | /customers/{*}/summary                 | ScoreController.summary                  |
| credit-api         | POST  | /credit-api/v1/credits/limit-checks    | CreditController.checkLimit              |
| credit-api         | GET   | /credit-api/v1/credits/{*}             | CreditController.getCredit               |
| credit-api         | PUT   | /credit-api/v1/credits/{*}/status      | CreditController.updateStatus            |
| credit-api         | GET   | /credit-api/v1/customers/{*}/exposure  | CustomerExposureController.getExposure   |
| sales-api          | POST  | /api/sales/offers                      | SalesController.createOffer              |
| sales-api          | GET   | /api/sales/customers/{*}/offers        | SalesController.listOffers               |
| core-adapter       | GET   | /core/customers/{*}                    | CoreCustomerController.getCustomer       |

## Feign → Endpoint

| Repo               | Feign metodu                          | Hedef                                              |
|--------------------|---------------------------------------|----------------------------------------------------|
| leasing-credit-bff | CreditApiClient.checkLimit            | credit-api POST /credit-api/v1/credits/limit-checks (ad `${clients.credit.name}` → `credit-api`) |
| leasing-credit-bff | CreditApiClient.getCredit             | credit-api GET /credit-api/v1/credits/{*}          |
| leasing-credit-bff | SalesApiClient.createOffer            | sales-api POST /api/sales/offers                   |
| leasing-credit-bff | SalesApiClient.listOffers             | sales-api GET /api/sales/customers/{*}/offers      |
| scoring-bff        | ExposureClient.getExposure            | credit-api GET /credit-api/v1/customers/{*}/exposure |
| scoring-bff        | SalesClient.listOffers                | sales-api GET /api/sales/customers/{*}/offers      |
| scoring-bff        | LegacyScoreClient.getLegacyScore      | **UNRESOLVED** (servis yok)                        |
| credit-api         | CoreBankingClient.getCustomer         | core-adapter GET /core/customers/{*}               |
| sales-api          | CoreClient.getCustomer                | core-adapter GET /core/customers/{*} (`path="/core"` + `/customers/{no}`) |

## ApiCall → BFF Endpoint

ApiCall'lar `callApi({ endpoint: getBaseUrl() + "/path", method })` kalıbındadır; `getBaseUrl()` BFF'in gateway
prefix'ini döndürür ve kendisi ApiCall değildir.

| ApiCall (dosya)                                       | Metot | Hedef                                   |
|-------------------------------------------------------|-------|-----------------------------------------|
| getCreditApplication (creditApplicationApiCalls.ts)   | GET   | leasing-credit-bff GET /applications/{*} |
| createCreditApplication (creditApplicationApiCalls.ts)| POST  | leasing-credit-bff POST /applications   |
| getApplicationDocument (creditApplicationApiCalls.ts) | GET   | **UNRESOLVED** (URL dinamik)            |
| getScore (scoringApiCalls.js)                         | GET   | scoring-bff GET /scores/{*}             |
| getCustomerSummary (scoringApiCalls.js)               | GET   | scoring-bff GET /customers/{*}/summary  |
| getScoreHistory (scoringApiCalls.js)                  | GET   | **UNRESOLVED** (BFF'te endpoint yok)    |

## Ekranlar ve component'ler

Ekranlar `src/routes.ts` dizisindeki `{ path, component: asyncComponent(() => import("./screens/X")) }` nesnelerinden
çıkarılır; component, import edilen modülün varsayılan export'udur. CreditApplicationScreen ApplicationForm'u JSX
etiketiyle değil, sekme dizisinden (`tabItems` + `<item.component />`) render eder.

| Route                   | Ekran component'i       | RENDERS                       | ApiCall kullanımı (sıra, via/hook) |
|-------------------------|-------------------------|-------------------------------|------------------------------------|
| /credit-application     | CreditApplicationScreen | ScoreBadge, ApplicationForm   | 1 getCreditApplication (REACT_QUERY/useQuery) |
| /customers/:customerId  | CustomerDetailScreen    | ScoreBadge                    | 1 getScore, 2 getCustomerSummary (REACT_QUERY/useQueries), 3 getScoreHistory (DIRECT, `await` ile) |
| —                       | ApplicationForm         | —                             | 1 createCreditApplication (REACT_QUERY/useMutation) |
| —                       | ScoreBadge              | —                             | 1 getScore (REACT_QUERY/`useScore > useQuery`: `shared/hooks` altındaki özel hook) |

`ScoreBadge.test.tsx` ve `credit-api/src/test/**` analiz dışıdır.

## Entity → Tablo

| Repo         | Entity            | Tablo                 | İlişkiler |
|--------------|-------------------|-----------------------|-----------|
| credit-api   | Credit            | LEASING.CREDIT        | ONE_TO_MANY → CreditInstallment (installments) |
| credit-api   | CreditInstallment | credit_installment    | MANY_TO_ONE → Credit (credit) |
| credit-api   | CustomerLimit     | customer_limit        | — |
| sales-api    | Offer             | offers                | MANY_TO_ONE → Dealer (dealer) |
| sales-api    | Dealer            | dealer                | MANY_TO_MANY → Region (regions) |
| sales-api    | Region            | region                | — |
| core-adapter | CustomerSnapshot  | CORE.CUSTOMER_SNAPSHOT| — |

## Repository metotları (READ/WRITE)

- `CreditRepository`: findByCustomerId READ, findAllWithStatus READ (`@Query` select), markRejected WRITE (`@Modifying`),
  miras: findById READ, save WRITE, …
- `CustomerLimitRepository`: findByCustomerId READ, existsByCustomerId READ
- `OfferRepository`: findAllByCustomerId READ; miras save WRITE
- `DealerRepository`: miras findById READ
- `CustomerSnapshotRepository`: findByCustomerNo READ

## Servis içi çağrı ağaçları (accessor'lar hariç göreli sıra)

Sıra numarası **çalışma sırasıdır**: argümanlardaki çağrılar dıştaki çağrıdan önce gelir. JDK/kütüphane çağrıları
(`stream`, `map`, `orElseThrow`, `CompletableFuture.runAsync` …) kaydedilmez. Lombok ve record accessor'ları
`isAccessor` olarak kaydedilir.

**leasing-credit-bff `ApplicationServiceImpl.createApplication`**
1. `validateRequest` (private) → `ValidationUtils.requireText` (static)
2. `toLimitRequest` (private)
3. `CreditApiClient.checkLimit` — INVOKES
4. `SalesApiClient.createOffer` — INVOKES, **conditional** (`limit.approved()`)
5. `audit` — **async**, **viaLambda**
6. `merge` (private)

**leasing-credit-bff `ApplicationServiceImpl.getApplication`**
1. `CreditApiClient.getCredit` — INVOKES
2. `SalesApiClient.listOffers` — INVOKES
3. `toOfferView` — viaLambda (method reference)

**Controller → servis:** `CreditApplicationController.createApplication` → `ApplicationService.createApplication`
tek implementasyonlu arayüz olduğu için doğrudan `ApplicationServiceImpl.createApplication`'a **RESOLVED** bağlanır
(aynısı credit-api `CreditController` → `CreditServiceImpl` için).

**credit-api `CreditServiceImpl.checkLimit`**
1. `validate` (private)
2. `CoreBankingClient.getCustomer` — INVOKES
3. `CustomerLimitRepository.findByCustomerId` — USES_REPOSITORY (READ)
4. `CreditRepository.markRejected` — USES_REPOSITORY (WRITE), **conditional**
5. `LimitCheckResult.rejected` (record'daki static fabrika metodu), **conditional**
6. `toResult` (private static)

**credit-api `CreditServiceImpl.updateStatus`**
1. `CreditRepository.findById` — USES_REPOSITORY (READ, miras)
2. `recalculate` — **inLoop**
3. `CreditRepository.save` — USES_REPOSITORY (WRITE, miras)

**credit-api `ExposureService.sum`**: kendini çağırır → **recursive**.

**scoring-bff `ScoreService.score`**
1. `ExposureClient.getExposure` — INVOKES
2. `RiskCalculator.calculate` → **AMBIGUOUS**: `DefaultRiskCalculator.calculate` ve `ConservativeRiskCalculator.calculate`
   (her ikisi de miras alınan `BaseRiskCalculator.normalize`'ı çağırır; biri niteliksiz, biri `this.` ile)

**scoring-bff `ScoreService.summary`**
1. `score` (aynı sınıf)
2. `SalesClient.listOffers` — INVOKES
3. `LegacyScoreClient.getLegacyScore` — INVOKES (Feign metodu hedefe UNRESOLVED)

**sales-api `OfferService.createOffer`**
1. `DealerRepository.findById` — READ (miras)
2. `CoreClient.getCustomer` — INVOKES
3. `OfferRepository.save` — WRITE (miras)
4. `notifyDealer` — **async**, **viaLambda**
5. `toDto`

## Uçtan uca referans izler

1. **/credit-application → tablolar:** CreditApplicationScreen → getCreditApplication → leasing-credit-bff
   GET /applications/{*} → credit-api GET /credit-api/v1/credits/{*} → `findById` READ **LEASING.CREDIT**;
   sales-api GET …/offers → `findAllByCustomerId` READ **offers**. ApplicationForm üzerinden createCreditApplication →
   POST /applications → credit-api limit-checks → core-adapter (READ **CORE.CUSTOMER_SNAPSHOT**), READ **customer_limit**,
   WRITE **LEASING.CREDIT** (koşullu); sales-api POST /offers → READ **dealer**, WRITE **offers**.
   ScoreBadge üzerinden getScore → scoring-bff → credit-api exposure → READ **LEASING.CREDIT**.
2. **Geri iz: offers tablosu** → OfferRepository.save / findAllByCustomerId → sales-api endpoint'leri →
   leasing-credit-bff ve scoring-bff → ekranlar: /credit-application, /customers/:customerId.
3. **Geri iz: CORE.CUSTOMER_SNAPSHOT** → core-adapter GET /core/customers/{*} → credit-api ve sales-api →
   leasing-credit-bff POST /applications → /credit-application (ApplicationForm).
