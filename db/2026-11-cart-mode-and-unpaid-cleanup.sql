-- ════════════════════════════════════════════════════════════════════
-- 1) แยกตะกร้าออนไลน์ / ทานในร้าน
-- 2) ล้าง order ที่ไม่เคยได้เงิน (หมดเวลา / ยกเลิก / จ่ายไม่สำเร็จ) ที่ค้างอยู่จากระบบเดิม
--
-- รันก่อน deploy backend รอบนี้ — โค้ดใหม่อ่าน/เขียน tb_cart.cart_mode
-- โค้ดเดิมยังทำงานได้หลังรัน (คอลัมน์มีค่า default) จึงรันก่อนได้อย่างปลอดภัย
-- รันซ้ำได้ ไม่เกิดผลซ้ำ
--
--   Supabase  : Dashboard → SQL Editor → วางทั้งไฟล์ → Run
--   localhost : VS Code → Run Task → "db: migrate local (cart mode + cleanup)"
-- ════════════════════════════════════════════════════════════════════

BEGIN;

-- ─── 1) tb_cart.cart_mode ────────────────────────────────────────────
-- ของที่อยู่ในตะกร้าตอนนี้ทั้งหมดถือเป็นตะกร้าออนไลน์
ALTER TABLE public.tb_cart ADD COLUMN IF NOT EXISTS cart_mode varchar(10) NOT NULL DEFAULT 'online';

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_tb_cart_mode') THEN
        ALTER TABLE public.tb_cart
            ADD CONSTRAINT chk_tb_cart_mode CHECK (cart_mode IN ('online', 'dinein'));
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_cart_email_mode ON public.tb_cart (email, cart_mode);

-- ─── 2) ล้าง order ที่ไม่ได้เงิน ──────────────────────────────────────
-- เก็บไว้: จ่ายแล้ว, รอตรวจยอดโอน, สลิปที่แอดมินปฏิเสธ (มี slip_image), order ที่มีประวัติคืนเงิน/คำขอยกเลิก
-- ทุกคำสั่งมีเงื่อนไขในตัวเอง ไม่ใช้ temp table — SQL Editor ของ Supabase อาจรันแยกทีละคำสั่ง

-- ออเดอร์โต๊ะที่ผูกกับบิลที่จะลบ → ปลดให้เรียกบิลใหม่ได้ (stock ไม่ต้องคืน — ของกินไปแล้ว / คืนไปแล้วตอนหมดเวลา)
UPDATE public.tb_dinein_orders
SET payment_status = NULL, bill_order_id = NULL
WHERE payment_status IS DISTINCT FROM 'paid'
  AND bill_order_id IN (
    SELECT o.id FROM public.tb_orders o
    WHERE o.payment_status IN ('expired', 'failed')
      AND o.slip_image IS NULL
      AND NOT EXISTS (SELECT 1 FROM public.tb_refunds r WHERE r.order_id = o.id)
      AND NOT EXISTS (SELECT 1 FROM public.tb_cancel_requests c WHERE c.order_id = o.id));

DELETE FROM public.tb_order_items
WHERE order_id IN (
    SELECT o.id FROM public.tb_orders o
    WHERE o.payment_status IN ('expired', 'failed')
      AND o.slip_image IS NULL
      AND NOT EXISTS (SELECT 1 FROM public.tb_refunds r WHERE r.order_id = o.id)
      AND NOT EXISTS (SELECT 1 FROM public.tb_cancel_requests c WHERE c.order_id = o.id));

-- ลบ order แล้วนับจำนวนที่ลบในคำสั่งเดียว
WITH gone AS (
    DELETE FROM public.tb_orders o
    WHERE o.payment_status IN ('expired', 'failed')
      AND o.slip_image IS NULL
      AND NOT EXISTS (SELECT 1 FROM public.tb_refunds r WHERE r.order_id = o.id)
      AND NOT EXISTS (SELECT 1 FROM public.tb_cancel_requests c WHERE c.order_id = o.id)
    RETURNING o.id
)
SELECT (SELECT count(*) FROM gone) AS deleted_unpaid_orders,
       (SELECT count(*) FROM public.tb_cart WHERE cart_mode = 'online') AS online_cart_rows;

COMMIT;
