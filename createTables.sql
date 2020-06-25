DROP TABLE IF EXISTS Reservations;
DROP TABLE IF EXISTS Users;

CREATE TABLE Users (
    username varchar(30) NOT NULL PRIMARY KEY,
    hash varchar(32),
    salt varchar(32),
    balance int
);

CREATE TABLE Reservations (
  id int IDENTITY(1,1) PRIMARY KEY,
  username varchar(30) FOREIGN KEY REFERENCES Users(username),
  itineraryId int NOT NULL,
  paid int, -- 1 means paid
  cancelled int, --1 means cancelled
  price int,
  dayofmonth int
);

