Our database design involves only two tables: reservations and users. Because User information needs to be consistent across terminals
(ex: two terminals cannot create users with the same username), we chose to store that data server-side. Because there is limited capacity
for each flight, and every booking expends some of that capacity, we chose to store reservation data server-side as well. We initially
planned to store all itinerary information server-side, but quickly realized that it would not be feasible, and it was much simpler to 
create a local Itinerary variable that stored transient information about the most recent search made by each terminal. 
For the purpose of reducing total calls to the database, we chose to include some redundancy in our reservations design. Had we chosen a 
less redundant schema (something like Reservations(rid int PK, f1_id, f2_id)), each time a user called reservations we would have to call 
both the flights and reservations database. It was much simpler for us and quicker for the user to have a richer amount of data in 
reservations. This also sped up the pay command. 
