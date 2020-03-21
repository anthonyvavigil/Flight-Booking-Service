CREATE TABLE Users
	(username varchar(20) PRIMARY KEY,
	hash varchar(80),
	salt varchar(80),
	balance int);

CREATE TABLE Reservations 
	(rid int PRIMARY KEY,
	 username varchar(20) REFERENCES Users(username),
         origin_city varchar(34),
         dest_city varchar(34),
         day_of_month int,
         f1_id int, 
         f2_id int,
	 amtPaid int,
	 amtUnpaid int);