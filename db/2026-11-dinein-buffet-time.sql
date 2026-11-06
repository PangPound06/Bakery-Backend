-- ════════════════════════════════════════════════════════════════════
-- บุฟเฟต์จำกัดเวลาสั่ง 100 นาที — เก็บประเภทการทานไว้กับออเดอร์โต๊ะ
--
-- backend ใช้ประเภทของออเดอร์แรกในมื้อเป็นตัวตัดสิน (ลูกค้าสลับเป็น à la carte กลางมื้อไม่ได้)
-- ออเดอร์เดิมที่ไม่มีค่า (NULL) ถือเป็น à la carte
--
-- รันก่อน deploy backend รอบนี้ / รันซ้ำได้
--   Supabase  : Dashboard → SQL Editor → วางทั้งไฟล์ → Run
--   localhost : pgAdmin → db_bakery → Query Tool → วางทั้งไฟล์ → Run
-- ════════════════════════════════════════════════════════════════════

ALTER TABLE public.tb_dinein_orders ADD COLUMN IF NOT EXISTS dine_type varchar(20);

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_tb_dinein_orders_dine_type') THEN
        ALTER TABLE public.tb_dinein_orders
            ADD CONSTRAINT chk_tb_dinein_orders_dine_type CHECK (dine_type IN ('alacarte', 'buffet'));
    END IF;
END $$;

SELECT count(*) AS dinein_orders, count(dine_type) AS with_dine_type FROM public.tb_dinein_orders;
