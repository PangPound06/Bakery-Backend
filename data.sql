SET statement_timeout = 0;
SET lock_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SELECT pg_catalog.set_config('search_path', '', false);
SET check_function_bodies = false;
SET client_min_messages = warning;
SET row_security = off;

-- tb_categories
COPY public.tb_categories (id, name, slug, icon, display_order, is_active, created_at) FROM stdin;
1	Bakery	bakery	🥐	1	t	2026-03-28 22:27:05.638221
2	Cake	cake	🍰	2	t	2026-03-28 22:27:05.638221
3	Drink	drink	☕	3	t	2026-03-28 22:27:05.638221
4	Food	food	🍛	4	t	2026-03-28 22:27:05.638221
17	Appetizer	appetizer	🍗	5	t	2026-03-31 13:07:36.622937
\.

-- tb_suppliers
COPY public.tb_suppliers (id, address, category, contact_name, created_at, email, name, note, payment_terms, phone, status, tax_id) FROM stdin;
1	บางซื่อ	อุปกรณ์	Mark	2026-03-23 14:38:18.493474	scg@company.com	SCG		Net 30 วัน	0745217127	active	4524520452452
2	รัชโยธิน	บรรจุภัณฑ์	Tech X	2026-03-23 15:16:56.091611	SCB@company.com	SCB Tech X		Net 15 วัน	0745274527	active	7424245240402
3	พระราม4	วัตถุดิบ	asdfghjhkl	2026-03-23 15:30:45.121642	GoFive@company.com	GoFive		ชำระทันที	0785527	active	1245245275275
4	ปากเกร็ด	บรรจุภัณฑ์	Jdai	2026-03-28 20:06:29.999722	Jdai@company.com	KBank		ชำระทันที	0645465416	active	5364135864153
\.

-- tb_admin
COPY public.tb_admin (id, email, password, fullname, role, created_at, status, address, phone) FROM stdin;
1	Mapin@empbakery.com	$2a$10$tv2TsBS5hMnvxNZYtAS.kONipJwqcp86OqjryW7h5OfS0AZovmrlC	Thitirat Korkaew	owner	2026-01-11 06:51:28.844109	active	Kmitl	05727575
2	M3k@empbakery.com	$2a$10$VYVS7WM5FuXmZfqGFdufyusovptRVdkEiNrp8fl5WqtYsKz0QJoV6	เมฆ วันชนะ	admin	2026-01-11 06:51:28.844109	active	\N	\N
3	oatdekwat@empbakery.com	$2a$10$JYHKrEg55Ml2WK6l3Ia2AOTpn9QpvBduc1Q9wNsLgEq67mZE1FVha	โอ๊ต เด็กวัด	waiter	2026-01-11 07:16:10.227216	inactive	\N	\N
6	tny@empbakery.com	$2a$10$adpY9MEn0zRX9WD6dBeb4.z2apFK/px6o4joz0wWnU4xgkToc0hpa	Thanayut หมูหมัก	commis_chef	2026-02-17 09:39:08.095322	active	\N	\N
7	xxx@empbakery.com	$2a$10$.LJJD3unk5LNM0sQJv2e5OhqKi3AaIImPPJTdsGSNYmrnFE3u6/pG	นามแฝง	bartender	2026-03-24 19:32:48.912904	active	\N	\N
8	hwei@empbakery.com	$2a$10$I6cizmsg2FVKx0yh.Vz1yutIzbclP7UAkrI3EZmPlY5r9HZgCZM16	Hwei	chef	2026-03-28 20:01:54.229869	active	\N	\N
\.

-- tb_userregister
COPY public.tb_userregister (id, email, password, fullname, auth_provider, google_id, profile_image) FROM stdin;
2	pondpond6645@gmail.com	$2a$10$bJtLvsdgqe3AAI8MsKZwW.nsNP/X5MJPWYzH6o5eE3wiLD7lFUi.i	Kritchai Chaiyakham	both	102180369663266455687	https://lh3.googleusercontent.com/a/ACg8ocKfJP0IV2ApmL1c6AXAlRyKyxzojNIvNF8bqiEFY6OUjnTamKY0=s96-c
10	Kritchaicm6645@gmail.com	$2a$10$hQJbOtUkXLMPQN7GFK5SEO00CW.7R7nSGS2SiGnHs1F1EUDXis8se	Kritchai Chaiyakham	both	112141647934311362906	https://lh3.googleusercontent.com/a/ACg8ocKPUvHJZF58M-NiBYQ-QcSjzGEXXz7AvtSeBi8HKdl7iB1BCQ=s96-c
13	s6604022610232@email.kmutnb.ac.th		KRITCHAI CHAIYAKHAM	google	100145780316552495180	https://lh3.googleusercontent.com/a/ACg8ocJWX1srKinRmlPz7fCGLCFDFKMNdKIrGeShIBPWEkCixxXvVA=s96-c
14	somchai007@gmail.com	$2a$10$ZGovYitiMuE05YO86XUlaujNZDNf9nAe/DuzQKIT9MpjdgZSAIfZG	สมชาย ใจดี	local	\N	\N
18	a@a.com	$2a$10$iw5TMcILY4NgBecEepeYHe60CyyHJ6tHf.DMRwXY4PHs/vKxQAoRO	aaa	local	\N	\N
19	bbb@gmail.com	$2a$10$7Oj7HFxETCmoNhXoWKvk7OGFSrfQTYh9aGPvVyxFT8l7Gix3ELkN2	bbb	local	\N	\N
\.

-- tb_user_profile
COPY public.tb_user_profile (id, address, email, fullname, phone, profile_image, updated_at, user_id) FROM stdin;
1	Bangna	pondpond6645@gmail.com	Kritchai Chaiyakham	0894321456	https://lh3.googleusercontent.com/a/ACg8ocIS4krw_cJcTwrh_PD2wSLpPGs2Bv2BgH_Qk0Hedcaj6teQ44Yg=s96-c	2026-04-07 20:55:17.018615	2
2	\N	s6604022610232@email.kmutnb.ac.th	KRITCHAI CHAIYAKHAM	\N	https://lh3.googleusercontent.com/a/ACg8ocJWX1srKinRmlPz7fCGLCFDFKMNdKIrGeShIBPWEkCixxXvVA=s96-c	2026-03-21 18:01:41.852029	13
4	Bangsue	Kritchaicm6645@gmail.com	Kritchai Chaiyakham	06541564156	\N	2026-03-30 19:10:16.612623	10
6	\N	a@a.com	aaa	\N	\N	2026-03-24 13:31:38.708207	18
\.

-- tb_products
COPY public.tb_products (id, name, price, category, image, type, description, "stockQuantity", "isAvailable", options, category_id) FROM stdin;
1	Cupcake Vanilla Cream	45.00	bakery	https://sugargeekshow.com/wp-content/uploads/2022/08/vanilla_cupcake_featured_blog.jpg	Baked	คัพเค้กวานิลลาหอมนุ่ม เนื้อฟูละมุนลิ้น	42	t	\N	1
2	Soft Butter Cookie	35.00	bakery	https://iambaker.net/wp-content/uploads/2022/12/butter-cookies.jpg	Cookies	คุกกี้เนยนุ่ม หอมกลิ่นเนยแท้	42	t	\N	1
3	Classic Chocolate Brownie	60.00	bakery	https://www.recipetineats.com/uploads/2016/08/Brownies_0.jpg	Bar Cookies	บราวนี้ช็อกโกแลตเข้มข้น หนึบนุ่มกำลังดี	45	t	\N	1
4	Croissant	40.00	bakery	https://delishglobe.com/wp-content/uploads/2024/11/Croissants-article.png	Sweet Breads	ครัวซองต์เนยสดหอมกรอบนอกนุ่มใน	42	t	\N	1
5	Blueberry Muffin	45.00	bakery	https://www.rainbownourishments.com/wp-content/uploads/2022/03/vegan-blueberry-muffins-1-1.jpg	Sweet Breads	มัฟฟินบลูเบอร์รี่ อบสดใหม่ทุกวัน	43	t	\N	1
6	Cinnamon Roll	45.00	bakery	https://mccormick.widen.net/content/megysgsour/jpeg/Holiday_Cinnamon-Rolls_1376x774.jpeg	Sweet Breads	ซินนามอนโรลหอมอบอุ่น ราดด้วยครีมชีส	42	t	\N	1
7	Chocolate Chip Cookie	40.00	bakery	https://www.alattefood.com/wp-content/uploads/2024/08/Double-Chocolate-Chip-Cookies-72.jpg	Cookies	คุกกี้ช็อกชิปกรอบนอกนุ่มใน	44	t	\N	1
9	Chocolate Cake	80.00	cake	https://sallysbakingaddiction.com/wp-content/uploads/2013/04/triple-chocolate-cake-4.jpg	Layer Cakes	เค้กช็อกโกแลตเข้มข้น ชั้นนุ่มฟู หวานกำลังดี	54	t	[{"name":"ชิ้น","extraPrice":0,"stockMultiplier":1},{"name":"1 ปอนด์","extraPrice":560,"stockMultiplier":8},{"name":"2 ปอนด์","extraPrice":1200,"stockMultiplier":16}]	2
10	Strawberry Shortcake	90.00	desserts	https://zhangcatherine.com/wp-content/uploads/2020/06/12001200.jpg	Layer Cakes	ช็อตเค้กสตรอว์เบอร์รี่ นุ่มละมุน หอมหวาน	54	t	[{"name":"ชิ้น","extraPrice":0,"stockMultiplier":1},{"name":"1 ปอนด์","extraPrice":630,"stockMultiplier":8},{"name":"2 ปอนด์","extraPrice":1350,"stockMultiplier":16}]	2
11	Tiramisu	80.00	cake	https://www.kingarthurbaking.com/sites/default/files/styles/featured_image/public/2023-03/Tiramisu_1426.jpg?itok=i3F-CRsz	Specialty Cakes	ทิรามิสุแท้ รสชาติออริจินัล หอมกาแฟ	54	t	[{"name":"ชิ้น","extraPrice":0,"stockMultiplier":1},{"name":"1 ปอนด์","extraPrice":560,"stockMultiplier":8},{"name":"2 ปอนด์","extraPrice":1200,"stockMultiplier":16}]	2
15	Blueberry Cheesecake	85.00	cake	https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcSVppWGuxCS_JfXUnprTQFsyAH7LkgsmywvYQ&s	Cheesecakes	ชีสเค้กบลูเบอร์รี่ สดชื่น หวานกลมกล่อม	56	t	[{"name":"ชิ้น","extraPrice":0,"stockMultiplier":1},{"name":"1 ปอนด์","extraPrice":595,"stockMultiplier":8},{"name":"2 ปอนด์","extraPrice":1275,"stockMultiplier":16}]	2
16	Black Forest Cake	110.00	cake	https://zhangcatherine.com/wp-content/uploads/2023/01/12001200-8.jpg	Layer Cakes	แบล็คฟอเรส ช็อกโกแลต เชอร์รี่ วิปครีม	55	t	[{"name":"ชิ้น","extraPrice":0,"stockMultiplier":1},{"name":"1 ปอนด์","extraPrice":770,"stockMultiplier":8},{"name":"2 ปอนด์","extraPrice":1650,"stockMultiplier":16}]	2
17	Espresso	55.00	drink	https://sumatocoffee.com/cdn/shop/articles/espresso_d93cf1fb-0d4d-4da2-877f-c8226560ea4a.png?v=1758145494&width=640	Coffee	เอสเพรสโซ่เข้มข้น หอมกรุ่นจากเมล็ดกาแฟคั่วสด	9999	t	[{"name":"ร้อน","extraPrice":0},{"name":"เย็น","extraPrice":5},{"name":"ปั่น","extraPrice":10}]	3
18	Cappuccino	55.00	drink	https://www.livingnorth.com/images/media/articles/food-and-drink/eat-and-drink/coffee.png?fm=pjpg&w=1000&q=95	Coffee	คาปูชิโน่นมฟองนุ่ม กลมกล่อม	9999	t	[{"name":"ร้อน","extraPrice":0},{"name":"เย็น","extraPrice":5}]	3
19	Latte	55.00	drink	https://upload.wikimedia.org/wikipedia/commons/thumb/9/98/Latte_with_winged_tulip_art.jpg/1200px-Latte_with_winged_tulip_art.jpg	Coffee	ลาเต้นมเนียนนุ่ม หวานมัน	9999	t	[{"name":"ร้อน","extraPrice":0},{"name":"เย็น","extraPrice":5}]	3
20	Americano	40.00	drink	https://coffee-slang.com/wp-content/uploads/2025/06/iced-americano-recipe-1024x683.webp	Coffee	อเมริกาโน่เย็น สดชื่น กระปรี้กระเปร่า	50	t	[{"name":"ร้อน","extraPrice":0},{"name":"เย็น","extraPrice":5}]	3
21	Thai Tea	45.00	drink	https://146117906.cdn6.editmysite.com/uploads/1/4/6/1/146117906/Q7WTCYL3HBXD7SBRBID2L6QI.jpeg	Tea	ชาไทยเย็น หวานมัน รสชาติเข้มข้น	9999	t	[{"name":"ร้อน","extraPrice":0},{"name":"เย็น","extraPrice":5},{"name":"ปั่น","extraPrice":10}]	3
22	Green Tea Latte	45.00	drink	https://cdn.loveandlemons.com/wp-content/uploads/2023/06/iced-matcha-latte.jpg	Tea	ชาเขียวลาเต้ หอมมัทฉะ ชื่นใจ	9999	t	[{"name":"ร้อน","extraPrice":0},{"name":"เย็น","extraPrice":5},{"name":"ปั่น","extraPrice":10}]	3
23	Orange Juice	50.00	drink	https://media.post.rvohealth.io/wp-content/uploads/2020/08/orange-juice-732x549-thumbnail.jpg	Juice	น้ำส้มคั้นสด วิตามินซีสูง	9999	t	[{"name":"เย็น","extraPrice":0}]	3
24	Strawberry Smoothie	50.00	drink	https://www.ourhappymess.com/wp-content/uploads/2024/06/Strawberry-Raspberry-square-featured.jpg	Smoothie	สมูทตี้สตรอว์เบอร์รี่ เย็นชื่นใจ	9999	t	[{"name":"ปั่น","extraPrice":0}]	3
25	Banana Cake	35.00	bakery	https://www.ubonsunflower.com/storage/tasuko-product/recipes/organic-cassava-flour-all-purpose/banana-cake/img-01.webp	Baked	เค้กกล้วยหอม	43	t	\N	1
26	Dark Cocoa	60.00	drink	https://www.tulipcocoarista.com/static/media/recipe/2023/11/08/17/cca0/1699437950.1716.jpg	Chocolate	โกโก้เย็น ท็อปปิ้งด้วยวีปครีม	9999	t	[{"name":"ร้อน","extraPrice":0},{"name":"เย็น","extraPrice":5},{"name":"ปั่น","extraPrice":10}]	3
31	Iced Chocolate	45.00	drink	https://res.cloudinary.com/djjzmoyxd/image/upload/v1774091332/bakery/31c9053b-2446-4e75-a6b3-183ae023790d.jpg	Chocolate	ช็อกโกแลตเย็น	9999	t	[{"name":"เย็น","extraPrice":0}]	3
32	Basil Chicken with Rice and Fried Egg	100.00	food	https://us-fbcloud.net/hottopic/data/1107/1107933.x7c0cp1p16pi.n3.webp	Thai	กะเพราไก่ไข่ดาว	9999	t	\N	4
33	Fried Rice with Shrimps	100.00	food	https://api2.krua.co/wp-content/uploads/2025/01/RT1863_SEOImage_1200x630.jpg	Thai	ข้าวผัดกุ้ง	9999	t	[{"name":"เล็ก","extraPrice":0,"stockMultiplier":1},{"name":"กลาง","extraPrice":100,"stockMultiplier":1},{"name":"ใหญ่","extraPrice":200,"stockMultiplier":1}]	4
34	Spaghetti with Minced Chicken Tomato Sauce	150.00	food	https://i0.wp.com/goterrestrial.com/wp-content/uploads/2024/02/image-2.png?resize=683%2C1024&ssl=1	Italian	สปาเก็ตตี้ซอสมะเขือเทศไก่สับ	9999	t	\N	4
35	Black Garlic Ramen	180.00	food	https://img.wongnai.com/p/1920x0/2024/02/06/358c5fe88ffb43d4ba1f6316793c5d06.jpg	Japanese	ราเมงซุปกระเทียม	9999	t	\N	4
36	TanTan Ramen	180.00	food	https://img.wongnai.com/p/400x0/2024/10/30/b80b3834da184ec69b87a582d336d89b.jpg	Japanese	ราเมงซุปกระดูกหมู รสชาติเผ็ดผสมมัน	9999	t	[{"name":"หมู (แห้ง)","extraPrice":0,"stockMultiplier":1},{"name":"หมู (น้ำ)","extraPrice":0,"stockMultiplier":1}]	4
37	Barbecue Pork Bun	30.00	appetizer	https://www.mk1642.com/getmetafile/367359fa-c1ac-47c1-bc2c-5235e8f18639/เตย-E-SourceCode-DEV_MK-12-CMS-PictureforWeb-Snack-T0021.aspx	Steam	ซาลาเปาหมูแดง	40	t	\N	17
38	Steamed Fish with Lime Sauce	250.00	food	https://i.ytimg.com/vi/836ZEKyDjTI/maxresdefault.jpg	Thai	ปลานึ่งมะนาว เปรี้ยวเผ็ด	9999	t	\N	4
39	Minced Pork Bun	30.00	appetizer	https://f.ptcdn.info/843/066/000/q0uk70669evGUDJrvn6U-o.jpg	Steam	ซาลาเปาหมูสับ	40	t	\N	17
50	Carrot Juice	50.00	drink	https://media.istockphoto.com/id/1190328673/th/รูปถ่าย/น้ำแครอทในแก้ว.jpg	Juice	น้ำแครอท	9999	t	[{"name":"เย็น","extraPrice":0}]	3
51	Soft Drink	30.00	drink	https://www.khaosod.co.th/wpapp/uploads/2022/01/background-3797911_1280-1.jpg	Other	น้ำอัดลม	48	t	\N	3
52	Water	20.00	drink	https://www.scimath.org/images/uploads/upload2/coldwater.jpg	Other	น้ำเปล่า	50	t	\N	3
53	Matcha Strawberry	65.00	drink	https://www.acozykitchen.com/wp-content/uploads/2025/03/StrawberryMatchaLatte-1.jpg	Tea	มัจฉะ สตรอว์เบอร์รี่	9999	t	[{"name":"เย็น","extraPrice":0}]	3
54	Eclairs	5.00	bakery	https://image.makewebcdn.com/makeweb/0/Ub8wb5z91/AG190225/4_3_เอแคลร์.webp	Choux Pastry	เอแคลร์ลูกกลมโต ไส้วานิลลา	70	t	\N	1
55	Hamburger	100.00	food	https://res.cloudinary.com/djjzmoyxd/image/upload/v1774869611/bakery/f07977f3-9847-40e5-b4d7-92e675b5525e.jpg	European	แฮมเบอร์เกอร์เบค่อน ชีส	9999	t	\N	4
56	Fried Snapper with Fish Sauce	250.00	food	https://aroifin.com/wp-content/uploads/2025/10/cover-30102025-deep-fried-sea-bass-fish-with-sweet-fish-sauce.webp	Thai	ปลากะพงทอดน้ำปลา	9999	t	\N	4
57	Wide Rice Noodles with Seafood in Gravy Sauce	120.00	food	https://i.ytimg.com/vi/FxhEudaYgM8/maxresdefault.jpg	Chinese	ราดหน้าทะเล เส้นใหญ่	9999	t	[{"name":"เล็ก","extraPrice":0,"stockMultiplier":1},{"name":"กลาง","extraPrice":120,"stockMultiplier":1},{"name":"ใหญ่","extraPrice":240,"stockMultiplier":1}]	4
59	Fried Chicken Wings	100.00	appetizer	https://www.maggi.co.th/sites/default/files/srh_recipes/22083cfb8eb29281fa1992e9aa589423.jpeg	Fry	ปีกไก่ทอด	9999	t	\N	17
60	Shrimp Tempura	120.00	appetizer	https://www.1376delivery.com/productimages/11_34..jpg	Fry	กุ้งเทมปุระ	9999	t	\N	17
61	Gyoza	100.00	appetizer	https://renewalprod.blob.core.windows.net/renewal-prod/cms/articles/content/batch006shutterstock1775919827jpg_2024-05-16-05-24-51.jpg	Other	เกี๊ยวญี่ปุ่นไส้หมูผสมผัก	9999	t	[{"name":"ทอด","extraPrice":0,"stockMultiplier":1},{"name":"นึ่ง","extraPrice":0,"stockMultiplier":1}]	17
62	Katsudon	150.00	food	https://i.ytimg.com/vi/lAayhRSqAYM/sddefault.jpg	Japanese	ข้าวเนื้อสัตว์ชุปแป้งทอด	9999	t	[{"name":"ไก่ (ปกติ)","extraPrice":0,"stockMultiplier":1},{"name":"ไก่ (พิเศษ)","extraPrice":50,"stockMultiplier":1},{"name":"หมูสันนอก (ปกติ)","extraPrice":0,"stockMultiplier":1},{"name":"หมูสันนอก (พิเศษ)","extraPrice":50,"stockMultiplier":1}]	4
63	Fish Maw Soup	200.00	food	https://aroifin.com/wp-content/uploads/2025/09/110925-fish-maw-soup-in-red-broth-cover.webp	Chinese	ซุปกระเพาะปลาน้ำแดง	9999	t	[{"name":"ปกติ","extraPrice":0,"stockMultiplier":1},{"name":"ใหญ่","extraPrice":100,"stockMultiplier":1}]	4
64	Peking Duck	1000.00	food	https://www.gourmetandcuisine.com/Images/editor_upload/_editor20181017101940_original.jpg	Chinese	เป็ดปักกิ่ง	9999	t	\N	4
65	Tomato Mozzarella Pizza	300.00	food	https://italianstreetkitchen.com/au/wp-content/uploads/2021/10/Gamberi-Prawn-Pizza.jpg	Italian	พิซซ่าซอสมะเขือเทศ	9999	t	[{"name":"กลาง","extraPrice":0,"stockMultiplier":1},{"name":"ใหญ่","extraPrice":150,"stockMultiplier":1}]	4
66	Okonomiyaki	65.00	appetizer	https://oyakata.com.pl/wp-content/uploads/2022/08/Japonskie-jedzenie-Pizza-Okonomiyaki.jpg	Grill	พิซซ่าญี่ปุ่น	9999	t	\N	17
67	Takoyaki	15.00	appetizer	https://digjapan.travel/files/topics/8003_ext_02_2.jpg	Grill	ทาโกะยากิ	9999	t	\N	17
68	Steamed Custard Bun	30.00	appetizer	https://www.seatech.co.th/wp-content/uploads/2018/02/-1-e1519381679255.jpg	Steam	ซาลาเปาไส้ครีม	40	t	\N	17
69	Penne	180.00	food	https://www.thecandidcooks.com/wp-content/uploads/2021/01/baked-penne-feature.jpg	Italian	พาสต้ารูปทรงกระบอก	9999	t	[{"name":"ราดซอสมะเขือเทศ","extraPrice":0,"stockMultiplier":1},{"name":"ราดครีม","extraPrice":0,"stockMultiplier":1}]	4
70	Lasagna	150.00	food	https://i0.wp.com/goterrestrial.com/wp-content/uploads/2024/02/image-16.png?w=564&ssl=1	Italian	ลาซานญ่า	9999	t	\N	4
71	Linguine	180.00	food	https://i0.wp.com/goterrestrial.com/wp-content/uploads/2024/02/image-23.png?w=564&ssl=1	Italian	ลิงกวีเน	9999	t	\N	4
72	Fettuccine	180.00	food	https://i0.wp.com/goterrestrial.com/wp-content/uploads/2024/02/image-29.png?w=564&ssl=1	Italian	เฟตตูชินี	9999	t	\N	4
73	Tomyum Ramen	180.00	food	https://www.lemon8-app.com/seo/image?item_id=7508259934463754760&index=2&sign=0f5afca45c2d7d657484f76b21280bb3	Japanese	ราเมงซุปต้มยำ	9999	t	[{"name":"กุ้ง (แห้ง)","extraPrice":0,"stockMultiplier":1},{"name":"กุ้ง (น้ำ)","extraPrice":0,"stockMultiplier":1},{"name":"หมู (แห้ง)","extraPrice":0,"stockMultiplier":1},{"name":"หมู (น้ำ)","extraPrice":0,"stockMultiplier":1}]	4
74	Tonkotsu Ramen	180.00	food	https://www.ryoiireview.com/upload/article/202302/1676276067_d0096ec6c83575373e3a21d129ff8fef.jpg	Japanese	ทงคัตสึราเมง	9999	t	\N	4
75	Katsu Curry	210.00	food	https://s359.kapook.com/pagebuilder/c5a27416-c833-4114-81d0-6393d25143f8.jpg	Japanese	ข้าวแกงกะหรี่	9999	t	[{"name":"ไก่ (ปกติ)","extraPrice":0,"stockMultiplier":1},{"name":"ไก่ (พิเศษ)","extraPrice":60,"stockMultiplier":1},{"name":"หมู (ปกติ)","extraPrice":0,"stockMultiplier":1},{"name":"หมู (พิเศษ)","extraPrice":60,"stockMultiplier":1}]	4
76	Curry Ramen	210.00	food	https://img.freepik.com/premium-photo/curry-ramen-noodles-with-tonkatsu-fried-pork-cutlet-japanese-food-style_1339-153654.jpg	Japanese	ราเมงแกงกะหรี่	9999	t	[{"name":"ไก่","extraPrice":0,"stockMultiplier":1},{"name":"หมู","extraPrice":0,"stockMultiplier":1}]	4
77	Hainanese Chicken Rice	100.00	food	https://img.wongnai.com/p/1920x0/2017/09/06/72b44e8c277a40529e65e2856271707e.jpg	Chinese	ข้าวมันไก่	9999	t	[{"name":"ต้ม","extraPrice":0,"stockMultiplier":1},{"name":"ทอด","extraPrice":0,"stockMultiplier":1},{"name":"ผสม","extraPrice":20,"stockMultiplier":1}]	4
78	Tom Yum Seafood	300.00	food	https://food-heal.com/wp-content/uploads/2023/06/1-1024x819.jpg	Thai	ต้มยำทะเล	9999	t	[{"name":"น้ำใส","extraPrice":0,"stockMultiplier":1},{"name":"น้ำข้น","extraPrice":0,"stockMultiplier":1}]	4
79	Thai Stewed Pork and Eggs with five spice	240.00	food	https://shopee.co.th/blog/wp-content/uploads/2020/08/Shopee-Blog-How-to-eggs-and-pork-in-brown-sauce-soup-1024x683.jpg	Thai	ไข่พะโล้	9999	t	[{"name":"ไข่ไก่","extraPrice":0,"stockMultiplier":1},{"name":"ไข่เป็ด","extraPrice":10,"stockMultiplier":1}]	4
80	Panang Curry	180.00	food	https://www.pholfoodmafia.com/wp-content/uploads/2020/11/6Beef-Panang-Curry.jpg	Thai	แกงพะแนง	9999	t	[{"name":"ไก่","extraPrice":0,"stockMultiplier":1},{"name":"หมู","extraPrice":0,"stockMultiplier":1},{"name":"เนื้อ","extraPrice":20,"stockMultiplier":1}]	4
81	Green Curry	180.00	food	https://s359.kapook.com/pagebuilder/6ac2ddd1-f67b-44f2-bd0a-fd4ab888efd6.jpg	Thai	แกงเขียวหวาน	9999	t	[{"name":"ไก่","extraPrice":0,"stockMultiplier":1},{"name":"หมู","extraPrice":0,"stockMultiplier":1},{"name":"เนื้อ","extraPrice":20,"stockMultiplier":1}]	4
82	Egg Noodles with Red Pork	120.00	food	https://www.siamfishing.com/_pictures/content/upload2022/202207/16590300653F.jpg	Chinese	บะหมี่หมูแดง	9999	t	[{"name":"เล็ก","extraPrice":0,"stockMultiplier":1},{"name":"กลาง","extraPrice":120,"stockMultiplier":1},{"name":"ใหญ่","extraPrice":240,"stockMultiplier":1}]	4
83	Barbecued Red Pork in Sauce with Rice	150.00	food	https://preview.redd.it/thai-barbecued-pork-on-rice-khao-moo-dang-is-truly-thai-v0-eqwanrw7ncye1.png?width=640&crop=smart&auto=webp&s=1701def044f047cc4522403117aaf27b5acc2171	Chinese	ข้าวหมูแดง	9999	t	\N	4
84	Fish and Chips	120.00	food	https://transcode-v2.app.engoo.com/image/fetch/f_auto,c_lfill,w_300,dpr_3/https://assets.app.engoo.com/images/2NLKENNqCkdh9SqQScj1du.jpeg	European	ปลาค็อดชุบแป้งทอด	9999	t	\N	4
85	Paella	240.00	food	https://image.makewebeasy.net/makeweb/r_1920x1920/vq7Oemz1i/DefaultData/1_59.jpg?v=202405291424	European	ข้าวผัดสเปน	9999	t	\N	4
86	German Pork Knuckle	300.00	food	https://easyworldrecipes.com/wp-content/uploads/2024/07/German-Pork-Knuckle-683x1024.jpg	European	ขาหมูเยอรมัน	9999	t	\N	4
87	Beef Steak	500.00	food	https://organicallyaddison.com/wp-content/uploads/2022/11/2022-11-14_22-46-49_030-2022-11-15T06_02_32.033.jpeg	European	สเต็กเนื้อวัว	9999	t	[{"name":"Ribeye","extraPrice":0,"stockMultiplier":1},{"name":"Sirloin","extraPrice":0,"stockMultiplier":1},{"name":"T-Bone","extraPrice":0,"stockMultiplier":1},{"name":"Tenderloin","extraPrice":0,"stockMultiplier":1}]	4
88	Cream Soup	60.00	appetizer	https://www.paleorunningmomma.com/wp-content/uploads/2020/11/creamy-cauliflower-soup-6.jpg	Soup	ซุปครีมเข้มข้น	77	t	[{"name":"เห็ด","extraPrice":0,"stockMultiplier":1},{"name":"ผักโขม","extraPrice":0,"stockMultiplier":1},{"name":"หัวหอม","extraPrice":0,"stockMultiplier":1}]	17
89	Salad	60.00	appetizer	https://www.eatingwell.com/thmb/S2NGMEcgm11dtdBJ6Hwprwq-nVk=/1500x0/filters:no_upscale():max_bytes(150000):strip_icc()/eat-the-rainbow-chopped-salad-with-basil-mozzarella-beauty-185-278133-4000x2700-56879ac756cd46ea97944768847b7ea5.jpg	Other	สลัดผัก	9999	t	\N	17
90	Garlic bread	40.00	appetizer	https://www.rosalynth.com/home/wp-content/uploads/2022/04/Air-Fryer-Garlic-Bread.jpg	Baked	ขนมปังแผ่นทาเนยกระเทียม	9999	t	\N	17
91	Steamed rice	20.00	appetizer	https://us-fbcloud.net/wb/data/1594/1594486-img.x16n5s.14n8f.webp	Other	ข้าวสวย	9999	t	\N	17
92	Steamed Oreo Cake	95.00	cake	https://images.squarespace-cdn.com/content/v1/54e9c782e4b02db31b4324d9/1701392720540-ZXMN046CL14C8U82NZDR/IMG_6001.jpg?format=2500w	Specialty Cakes	โอรีโอ้เค้ก	60	t	[{"name":"ชิ้น","extraPrice":0,"stockMultiplier":1},{"name":"1 ปอนด์","extraPrice":665,"stockMultiplier":8},{"name":"2 ปอนด์","extraPrice":1425,"stockMultiplier":16}]	2
93	Orange cake	80.00	cake	https://www.savvymamalifestyle.com/wp-content/uploads/2020/01/Orange-julius-cake-1-3-scaled.jpg	Layer Cakes	เค้กส้ม	58	t	[{"name":"ชิ้น","extraPrice":0,"stockMultiplier":1},{"name":"1 ปอนด์","extraPrice":560,"stockMultiplier":8},{"name":"2 ปอนด์","extraPrice":1200,"stockMultiplier":16}]	2
94	Dark Chocolate Cake	95.00	cake	https://static.vecteezy.com/system/resources/thumbnails/058/201/648/small/decadent-chocolate-cake-drizzled-with-rich-chocolate-ganache-and-topped-with-chopped-chocolate-pieces-a-close-up-view-highlights-the-smooth-glossy-chocolate-finish-and-moist-cake-base-photo.jpg	Layer Cakes	ดาร์กช็อกโกแลต	62	t	[{"name":"ชิ้น","extraPrice":0,"stockMultiplier":1},{"name":"1 ปอนด์","extraPrice":665,"stockMultiplier":8},{"name":"2 ปอนด์","extraPrice":1425,"stockMultiplier":16}]	2
\.

-- Sequences
SELECT pg_catalog.setval('public.tb_admin_id_seq', 9, true);
SELECT pg_catalog.setval('public.tb_bakery_id_seq', 95, true);
SELECT pg_catalog.setval('public.tb_cart_id_seq', 423, true);
SELECT pg_catalog.setval('public.tb_categories_id_seq', 17, true);
SELECT pg_catalog.setval('public.tb_dinein_order_items_id_seq', 62, true);
SELECT pg_catalog.setval('public.tb_dinein_orders_id_seq', 38, true);
SELECT pg_catalog.setval('public.tb_favorites_id_seq', 7, true);
SELECT pg_catalog.setval('public.tb_order_items_id_seq', 298, true);
SELECT pg_catalog.setval('public.tb_orders_id_seq', 198, true);
SELECT pg_catalog.setval('public.tb_purchase_orders_id_seq', 5, true);
SELECT pg_catalog.setval('public.tb_suppliers_id_seq', 4, true);
SELECT pg_catalog.setval('public.tb_user_profile_id_seq', 6, true);
SELECT pg_catalog.setval('public.user_entity_id_seq', 19, true);