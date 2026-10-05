-- Fresh identity column, so these get ids 1..5 (the simulator publishes to fleet/1..5/telemetry).
INSERT INTO vehicles (registration, make, model, year, status) VALUES
    ('KA01AB1234', 'Tata',          'Prima 5530.S',  2021, 'ACTIVE'),
    ('KA02CD5678', 'Ashok Leyland', 'Ecomet 1615',   2020, 'ACTIVE'),
    ('MH12EF9012', 'Mahindra',      'Blazo X 35',    2022, 'ACTIVE'),
    ('TN09GH3456', 'Eicher',        'Pro 3015',      2019, 'ACTIVE'),
    ('DL01JK7890', 'BharatBenz',    '1917R',         2023, 'ACTIVE');
