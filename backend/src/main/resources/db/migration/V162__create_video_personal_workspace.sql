CREATE TABLE video_user_favorites (
    owner_key VARCHAR(80) NOT NULL,
    asset_id BIGINT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (owner_key, asset_id),
    KEY idx_video_favorite_asset (asset_id),
    CONSTRAINT fk_video_favorite_asset FOREIGN KEY (asset_id) REFERENCES product_assets(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE video_recent_views (
    owner_key VARCHAR(80) NOT NULL,
    asset_id BIGINT NOT NULL,
    view_count INT NOT NULL DEFAULT 1,
    last_viewed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (owner_key, asset_id),
    KEY idx_video_recent_owner_time (owner_key, last_viewed_at),
    CONSTRAINT fk_video_recent_asset FOREIGN KEY (asset_id) REFERENCES product_assets(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE video_collections (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    owner_key VARCHAR(80) NOT NULL,
    name VARCHAR(120) NOT NULL,
    description VARCHAR(500) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_video_collection_owner_name (owner_key, name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE video_collection_items (
    collection_id BIGINT NOT NULL,
    asset_id BIGINT NOT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    added_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (collection_id, asset_id),
    KEY idx_video_collection_item_asset (asset_id),
    CONSTRAINT fk_video_collection_item_collection FOREIGN KEY (collection_id) REFERENCES video_collections(id) ON DELETE CASCADE,
    CONSTRAINT fk_video_collection_item_asset FOREIGN KEY (asset_id) REFERENCES product_assets(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
