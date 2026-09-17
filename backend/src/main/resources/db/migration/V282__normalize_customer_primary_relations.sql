-- 历史回填曾为同一客户生成多个“主要联系人”和重复“主责业务员”，先收敛再加约束。
CREATE TABLE customer_contacts_before_v282 LIKE customer_contacts;
INSERT INTO customer_contacts_before_v282
SELECT c.* FROM customer_contacts c
JOIN (SELECT customer_id FROM customer_contacts WHERE is_primary=1 GROUP BY customer_id HAVING COUNT(*)>1) x
  ON x.customer_id=c.customer_id
WHERE c.is_primary=1;

CREATE TABLE customer_salesperson_relations_before_v282 LIKE customer_salesperson_relations;
INSERT INTO customer_salesperson_relations_before_v282
SELECT r.* FROM customer_salesperson_relations r
JOIN (
  SELECT customer_id FROM customer_salesperson_relations
  GROUP BY customer_id HAVING SUM(role='PRIMARY')>1
) x ON x.customer_id=r.customer_id
UNION
SELECT r.* FROM customer_salesperson_relations r
JOIN (
  SELECT customer_id,COALESCE(employee_id,salesperson_id,0) person_id,
         LOWER(TRIM(salesperson_name)) salesperson_name,role,
         COALESCE(start_date,'1000-01-01') start_date
  FROM customer_salesperson_relations
  GROUP BY customer_id,COALESCE(employee_id,salesperson_id,0),LOWER(TRIM(salesperson_name)),role,
           COALESCE(start_date,'1000-01-01')
  HAVING COUNT(*)>1
) x ON x.customer_id=r.customer_id
   AND x.person_id=COALESCE(r.employee_id,r.salesperson_id,0)
   AND x.salesperson_name=LOWER(TRIM(r.salesperson_name))
   AND x.role=r.role AND x.start_date=COALESCE(r.start_date,'1000-01-01');

UPDATE customer_contacts c
JOIN (
  SELECT id,ROW_NUMBER() OVER (PARTITION BY customer_id ORDER BY created_at,id) rn
  FROM customer_contacts WHERE is_primary=1
) ranked ON ranked.id=c.id
SET c.is_primary=0
WHERE ranked.rn>1;

DELETE duplicate_row
FROM customer_salesperson_relations duplicate_row
JOIN customer_salesperson_relations keep_row
  ON keep_row.customer_id=duplicate_row.customer_id
 AND COALESCE(keep_row.employee_id,keep_row.salesperson_id,0)=COALESCE(duplicate_row.employee_id,duplicate_row.salesperson_id,0)
 AND LOWER(TRIM(keep_row.salesperson_name))=LOWER(TRIM(duplicate_row.salesperson_name))
 AND keep_row.role=duplicate_row.role
 AND COALESCE(keep_row.start_date,'1000-01-01')=COALESCE(duplicate_row.start_date,'1000-01-01')
 AND keep_row.id<duplicate_row.id;

UPDATE customer_salesperson_relations r
JOIN (
  SELECT id,ROW_NUMBER() OVER (
    PARTITION BY customer_id
    ORDER BY (end_date IS NULL) DESC,start_date DESC,id DESC
  ) rn
  FROM customer_salesperson_relations WHERE role='PRIMARY'
) ranked ON ranked.id=r.id
SET r.role='COLLABORATOR'
WHERE ranked.rn>1;

ALTER TABLE customer_contacts
  ADD COLUMN primary_guard TINYINT GENERATED ALWAYS AS (CASE WHEN is_primary=1 THEN 1 ELSE NULL END) STORED,
  ADD UNIQUE KEY uk_customer_contact_primary (customer_id,primary_guard);

ALTER TABLE customer_salesperson_relations
  ADD COLUMN primary_guard TINYINT GENERATED ALWAYS AS (CASE WHEN role='PRIMARY' THEN 1 ELSE NULL END) STORED,
  ADD UNIQUE KEY uk_customer_salesperson_primary (customer_id,primary_guard);
