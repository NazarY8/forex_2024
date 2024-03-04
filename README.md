## Implementation of Paidy Forex challenge.

### What was done? 👀️

1. The service returns an exchange rate when provided with 2 supported currencies
2. The rate cannot be older than 5 minutes, but in the case of the same request within 5 minutes - you will receive information from cache.
3. The service support at 10,000 successful requests per day


### How tu run? 🚀️

1. clone git repo, `git clone ..`
2. run docker container,`docker run -p 8080:8080 paidyinc/one-frame`
3. from project folder, run `sbt compile`
4. from project folder, run `sbt run`
5. Then use Postman or any other apps for requests.
   User cases:

   `http://localhost:10000/rates?from=USD&to=EUR`

   `http://localhost:10000/rates?from=USD&to=USD`

   `http://localhost:10000/rates?from=USD1&to=EUR`

   `http://localhost:10000/rates?from=USD&to=EUR1`

### How to test? 🎉️

1. from project folder, run `sbt test`
   